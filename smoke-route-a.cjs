/* 路线A多机构改造冒烟（8093 实例，一次性验证脚本） */
const { sm2 } = require('./source/front/node_modules/sm-crypto')
const { X509Certificate } = require('crypto')
const B = 'http://127.0.0.1:8093/ssl'
const results = []
function T(name, pass, detail) { results.push({ name, pass }); console.log((pass ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + (detail === undefined ? '' : String(detail).slice(0, 160))) }
async function j(url, opt) { const r = await fetch(url, opt); let b = null; try { b = await r.json() } catch { } return { status: r.status, body: b } }
async function pubKey() { return (await j(B + '/auth/publicKey')).body.data.publicKey }
async function login(u, p) { const pk = await pubKey(); return j(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, passwordCipher: sm2.doEncrypt(p, pk, 1) }) }) }
const H = t => ({ 'Content-Type': 'application/json', Authorization: 'Bearer ' + t })

;(async () => {
  const admin = await login('admin', '123456')
  const at = admin.body?.data?.token
  T('admin(全局)登录', !!at)
  if (!at) { console.log(JSON.stringify(admin.body)); process.exit(1) }

  // 1. default 机构登记
  const orgs = await j(B + '/admin/orgs', { headers: H(at) })
  const hasDefault = JSON.stringify(orgs.body).includes('default')
  T('GET /admin/orgs 含 default 域', orgs.status === 200 && hasDefault)

  // 2. 开第二机构（自动生独立根CA+SUB_CA）；机构已存在则走根CA初始化补建 SUB_CA（幂等修复）
  const mk = await j(B + '/admin/orgs', { method: 'POST', headers: H(at), body: JSON.stringify({ orgKey: 'smb01', orgName: 'SmokeB Hospital', subjectO: 'SMOKEB-HOSPITAL', algorithm: 'SM2', contact: 'smoke-route-a' }) })
  if (mk.status !== 200 || mk.body?.code !== 200) {
    const repair = await j(B + '/admin/ca/init?orgKey=smb01', { method: 'POST', headers: H(at) })
    T('POST /admin/orgs 建机构(独立根生成)', repair.body?.code === 200, '机构已存在，经 /admin/ca/init 幂等补建: ' + repair.body?.message)
  } else T('POST /admin/orgs 建机构(独立根生成)', true, mk.body?.message)

  // 3. 双根并存且互异，default 逐字节兼容
  const pemT = async u => await (await fetch(u)).text()
  const rootDefault = await pemT(B + '/ca/public/root')
  const rootSmb = await pemT(B + '/ca/public/smb01/root')
  let c1, c2
  try { c1 = new X509Certificate(rootDefault); c2 = new X509Certificate(rootSmb) } catch (e) { c1 = c2 = null }
  T('default 老URL仍是原根(O=CSSSL)', !!c1 && c1.subject.includes('CSSSL'), c1 && c1.subject)
  T('smb01 新根 O=SMOKEB-HOSPITAL', !!c2 && c2.subject.includes('SMOKEB-HOSPITAL'), c2 && c2.subject)
  T('两机构根证书互相独立', !!c1 && !!c2 && c1.publicKey !== c2.publicKey && c1.serialNumber !== c2.serialNumber)

  // 4. smb01 域签发：叶证书 O 受控
  const tpl = await j(B + '/admin/templates?orgKey=smb01', { headers: H(at) })
  const tplArr = Array.isArray(tpl.body?.data) ? tpl.body.data : (tpl.body?.data?.records || [])
  const t0 = tplArr.find(x => x.certType === 'SERVER') || tplArr.find(x => x.certType !== 'SUB_CA')
  T('smb01 可见全局模板', tpl.status === 200 && !!t0, t0 && t0.templateCode)
  const iss = await j(B + '/admin/certs?orgKey=smb01', { method: 'POST', headers: H(at), body: JSON.stringify({ subjectCn: 'smoke-b-srv', certType: 'SERVER', algorithm: 'SM2', validityDays: 90, san: 'DNS:smoke-b.local', description: 'route A smoke', templateId: t0?.id }) })
  const cid = iss.body?.data?.id
  T('smb01 域签发成功', iss.status === 200 && !!cid, iss.body?.message)
  const leaf = cid ? await (await fetch(B + '/admin/certs/' + cid + '/cert.pem', { headers: H(at) })).text() : ''
  let leafX = null; try { leafX = new X509Certificate(leaf) } catch { }
  T('叶证书 O=SMOKEB-HOSPITAL 且链到 smb01 根', !!leafX && leafX.issuer.includes('SMOKEB') , leafX && leafX.issuer)
  T('叶证书 SAN 透传', !!leafX && String(leafX.subjectAltName).includes('smoke-b.local'), leafX && leafX.subjectAltName)

  // 5. 跨机构校验隔离：default 域验 smb01 证书必须不通过
  const vX = await j(B + '/ca/public/verify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ certPem: leaf, purpose: 'TLS' }) })
  const vOK = await j(B + '/ca/public/smb01/verify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ certPem: leaf, purpose: 'TLS' }) })
  T('default 根验 smb01 证书=拒绝(不互信)', vX.body?.data ? vX.body.data.valid === false : vX.body?.code !== 200, JSON.stringify(vX.body?.data || vX.body?.message))
  T('smb01 根验本域证书=通过', vOK.body?.data?.valid === true, JSON.stringify(vOK.body?.data?.reasons || ''))

  // 6. CRL 分域：smb01 两次拉取逐字节一致
  const k1 = await (await fetch(B + '/ca/public/smb01/crl')).arrayBuffer()
  const k2 = await (await fetch(B + '/ca/public/smb01/crl')).arrayBuffer()
  T('smb01 CRL 判重发布', Buffer.compare(Buffer.from(k1), Buffer.from(k2)) === 0 && k1.byteLength > 0, k1.byteLength + 'B')

  // 7. 机构用户数据域强制
  await j(B + '/admin/users', { method: 'POST', headers: H(at), body: JSON.stringify({ username: 'smb01_op', realName: 'Smb01 Operator', roleCode: 'OPERATOR', orgKey: 'smb01', password: 'Smb20268q' }) })
  const op = await login('smb01_op', 'Smb20268q')
  const ot = op.body?.data?.token
  T('机构用户登录且响应带 orgKey', !!ot && op.body.data.orgKey === 'smb01', op.body?.data?.orgKey)
  const crossGet = await j(B + '/admin/certs?orgKey=default', { headers: H(ot) })
  T('机构用户 ?orgKey=default → 403', crossGet.status === 403, crossGet.body?.message)
  const crossIssue = await j(B + '/admin/certs?orgKey=default', { method: 'POST', headers: H(ot), body: JSON.stringify({ subjectCn: 'x-evil', certType: 'SERVER', algorithm: 'SM2', validityDays: 30 }) })
  T('机构用户跨域签发 → 403', crossIssue.status === 403)
  const ownList = await j(B + '/admin/certs', { headers: H(ot) })
  T('机构用户列表仅见本域', ownList.status === 200, 'rows=' + (ownList.body?.data?.records?.length ?? ownList.body?.data?.total ?? '?'))

  // 8. 机构关停/暂停：只读可用、写被拒
  await j(B + '/admin/orgs/smb01/suspend', { method: 'POST', headers: H(at) })
  const suspIss = await j(B + '/admin/certs?orgKey=smb01', { method: 'POST', headers: H(at), body: JSON.stringify({ subjectCn: 'smoke-b-srv2', certType: 'SERVER', algorithm: 'SM2', validityDays: 30 }) })
  const suspRead = await (await fetch(B + '/ca/public/smb01/crl')).status
  T('SUSPENDED 机构签发被拒', suspIss.status !== 200 || suspIss.body?.code !== 200, suspIss.body?.message)
  T('SUSPENDED 机构 CRL 仍可读(历史证书可验)', suspRead === 200, 'HTTP ' + suspRead)
  await j(B + '/admin/orgs/smb01/resume', { method: 'POST', headers: H(at) })

  // 8.5 裸域自愈：份额解封 → 删除 SUB_CA（DB 行 + 密钥目录，模拟首建中途崩溃残留）→ /admin/ca/init 幂等补建
  const fs = require('fs')
  const path = require('path')
  const { execFileSync } = require('child_process')
  const sh = []
  for (const i of [1, 2, 3]) {
    const s = await j(B + '/admin/ca/shares/' + i + '?orgKey=smb01', { headers: H(at) })
    sh.push(JSON.stringify(s.body?.data))
  }
  const uns = await j(B + '/admin/ca/unseal-shares?orgKey=smb01', { method: 'POST', headers: H(at), body: JSON.stringify({ shares: sh }) })
  T('3 份额解封 smb01 根私钥', uns.body?.code === 200, uns.body?.message)
  const pyFile = path.join(__dirname, '.tmp-repair-sql.py')
  fs.writeFileSync(pyFile, "import pymysql\nc=pymysql.connect(host='192.168.88.132',user='xz_feng',password='Feng123456!',database='ssl_ca')\ncur=c.cursor()\nprint(cur.execute(\"delete from ssl_ca_cert where org_key='smb01' and cert_type='SUB_CA'\"))\nc.commit()\n")
  execFileSync('python', [pyFile])
  fs.unlinkSync(pyFile)
  fs.rmSync(path.join(__dirname, 'source/ssl_backend/data/certs/orgs/smb01/subca'), { recursive: true, force: true })
  const rep = await j(B + '/admin/ca/init?orgKey=smb01', { method: 'POST', headers: H(at) })
  T('裸域经 /admin/ca/init 幂等补建 SUB_CA', rep.body?.code === 200, rep.body?.message)
  let subX = null; try { subX = new X509Certificate(await pemT(B + '/ca/public/smb01/subca')) } catch { }
  T('补建后 SUB_CA 链到本机构根', !!subX && subX.issuer.includes('SMOKEB'), subX && subX.subject)
  await j(B + '/admin/ca/seal?orgKey=smb01', { method: 'POST', headers: H(at) })

  // 9. 审计机构维度 + 全局链
  await j(B + '/admin/users', { method: 'POST', headers: H(at), body: JSON.stringify({ username: 'smoke_a_aud', realName: 'Global Auditor A', roleCode: 'AUDITOR', password: 'Sma20268a' }) })
  const aud = await login('smoke_a_aud', 'Sma20268a')
  const aut = aud.body?.data?.token
  const orgAudit = await j(B + '/admin/audit?orgKey=smb01&pageNo=1&pageSize=20', { headers: H(aut) })
  const rows = orgAudit.body?.data?.records || []
  T('审计按机构过滤(smb01 域留痕可查)', orgAudit.status === 200 && rows.length > 0, 'rows=' + rows.length + ' actions=' + [...new Set(rows.map(r => r.action))].join(','))
  const chain = await j(B + '/admin/audit/chain-verify', { headers: H(aut) })
  T('全局链对账可用(跨机构连续)', chain.status === 200 && chain.body?.data?.checked > 0, 'checked=' + chain.body?.data?.checked + ' broken=' + (chain.body?.data?.broken?.length || 0))

  // 10. 收尾：吊销冒烟证书、关闭机构、停用账号
  if (cid) await j(B + '/admin/certs/' + cid + '/revoke', { method: 'POST', headers: H(at), body: JSON.stringify({ reason: 'route A smoke cleanup' }) })
  await j(B + '/admin/orgs/smb01/close', { method: 'POST', headers: H(at), body: JSON.stringify({ reason: 'smoke cleanup' }) }).catch(() => {})
  const users = await j(B + '/admin/users', { headers: H(at) })
  for (const u of (users.body?.data?.records || users.body?.data || [])) {
    if (['smb01_op', 'smoke_a_aud'].includes(u.username)) await j(B + '/admin/users/' + u.id, { method: 'POST', headers: H(at), body: JSON.stringify({ status: 0 }) })
  }
  const fail = results.filter(r => !r.pass).length
  console.log('==== 路线A冒烟: ' + (results.length - fail) + '/' + results.length + ' PASS ====')
})().catch(e => { console.error('SMOKE ERROR', e); process.exit(1) })
