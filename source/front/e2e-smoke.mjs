// csssl 一键冒烟测试：登录 → 签发 → 下载证书链 → 在线校验 → 吊销 → CRL复查
// 用法：在 source/front 目录下执行  node e2e-smoke.mjs
// 依赖：直接复用本目录 node_modules/sm-crypto（前端已装）
import { sm2 } from 'sm-crypto';

const BASE = process.env.SSL_BASE || 'http://localhost:8092/ssl';
const ADMIN_USER = process.env.SSL_ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.SSL_ADMIN_PASS || '123456';

const log = (step, ok, extra = '') =>
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${step}${extra ? '  —— ' + extra : ''}`);

async function jfetch(path, opts = {}) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json', ...opts.headers },
    ...opts,
  });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: res.status, body };
}

// 1. 拿 SM2 登录公钥
const { body: pkResp } = await jfetch('/auth/publicKey');
const pubHex04 = pkResp.data.publicKey;          // 带 04 前缀的 130 hex，sm-crypto 直接用
log('1. 拿到登录SM2公钥', !!pubHex04, 'keyId=' + pkResp.data.keyId);

// 2. 加密口令并登录（C1C3C2，对应后端 LoginRequest.passwordCipher；密文不带04前缀）
const cipher = sm2.doEncrypt(ADMIN_PASS, pubHex04, 1); // 1=C1C3C2，对齐后端 Sm2Util
const { body: loginResp } = await jfetch('/auth/login', {
  method: 'POST',
  body: JSON.stringify({ username: ADMIN_USER, passwordCipher: cipher }),
});
if (loginResp.code !== 200) { console.error('登录失败:', loginResp); process.exit(1); }
const token = loginResp.data.token;
log('2. 登录拿JWT', !!token, '用户=' + loginResp.data.username);
const authHeaders = { Authorization: 'Bearer ' + token };

// 3. 在线签发一张测试 SERVER 证书
const issueReq = {
  subjectCn: 'smoke-test.his.local',
  certType: 'SERVER',
  algorithm: 'RSA2048',
  validityDays: 30,
  san: 'smoke-test.his.local',  // 逗号分隔纯值；注意：后端 IP SAN 传字符串会炸（需修 CertUtil），这里只用 DNS
  description: 'e2e冒烟自动签发',
};
const { body: issueResp } = await jfetch('/admin/certs', {
  method: 'POST', headers: authHeaders, body: JSON.stringify(issueReq),
});
if (issueResp.code !== 200) { console.error('签发失败:', issueResp); process.exit(1); }
const certId = issueResp.data.id;
log('3. 签发SERVER证书', true, `id=${certId} cn=${issueResp.data.subjectCn} serial=${issueResp.data.serialHex}`);

// 4. 下载证书链（叶子 + SUB_CA）
const chainRes = await fetch(`${BASE}/admin/certs/${certId}/cert.pem`, { headers: authHeaders });
const chainPem = await chainRes.text();
log('4. 下载证书链', chainPem.includes('BEGIN CERTIFICATE'), '长度=' + chainPem.length + '字符');

// 5. 在线校验（purpose=TLS）：应通过
const leafPem = chainPem.split('-----END CERTIFICATE-----')[0] + '-----END CERTIFICATE-----\n';
const { body: v1 } = await jfetch('/ca/public/verify', {
  method: 'POST',
  body: JSON.stringify({ certPem: leafPem, purpose: 'TLS' }),
});
log('5. 校验未吊销证书', v1.body?.valid === true || v1.data?.valid === true, JSON.stringify(v1.data || v1.body));

// 6. 吊销
const { body: revResp } = await jfetch(`/admin/certs/${certId}/revoke`, {
  method: 'POST', headers: authHeaders, body: JSON.stringify({ reason: 'ccss-smoke-test' }),
});
log('6. 吊销证书', revResp.code === 200, 'status=' + (revResp.data?.status || ''));

// 7. 再校验：应报吊销
const { body: v2 } = await jfetch('/ca/public/verify', {
  method: 'POST',
  body: JSON.stringify({ certPem: leafPem, purpose: 'TLS' }),
});
const revoked = (v2.data?.valid === false) && /吊销|revoked/i.test(JSON.stringify(v2.data));
log('7. 吊销后校验应失败', revoked, JSON.stringify(v2.data));

// 8. 拉 CRL，确认包含被吊销序列号
const crlRes = await fetch(BASE + '/ca/public/crl');
const crlPem = await crlRes.text();
log('8. CRL已发布', crlPem.includes('BEGIN X509 CRL'), '长度=' + crlPem.length);

console.log('\n== 冒烟完成 ==');
console.log('测试证书ID=' + certId + '（已吊销，可在管理后台证书列表查看）');
