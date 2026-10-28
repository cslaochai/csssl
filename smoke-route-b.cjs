/* 路线B改造冒烟（8093 实例，一次性验证脚本，验证完可删） */
const { sm2 } = require('./source/front/node_modules/sm-crypto')
const B = 'http://127.0.0.1:8093/ssl'
const results = []
function T(name, pass, detail) { results.push({ name, pass, detail }); console.log((pass ? 'PASS' : 'FAIL') + ' | ' + name + ' | ' + (detail || '')) }
async function j(url, opt) { const r = await fetch(url, opt); let b = null; try { b = await r.json() } catch { } return { status: r.status, body: b } }
async function pubKey() { const r = await j(B + '/auth/publicKey'); return r.body.data.publicKey }
function enc(pk, plain) { return sm2.doEncrypt(plain, pk, 1) }
async function login(u, p) { const pk = await pubKey(); return j(B + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: u, passwordCipher: enc(pk, p) }) }) }
const H = (t) => ({ 'Content-Type': 'application/json', Authorization: 'Bearer ' + t })

;(async () => {
  // 1. 公开端点
  const pk = await pubKey()
  T('GET /auth/publicKey', !!pk, 'SM2公钥长度 ' + (pk || '').length)

  // 2. admin 登录（共享开发库，口令可能被测试期改过；失败则记 SKIP 后继续能测的）
  let admin = await login('admin', '123456')
  if (admin.status !== 200 || admin.body?.code !== 200) {
    T('admin/123456 登录', false, JSON.stringify(admin.body))
    console.log('!! admin 口令非默认，后续依赖 admin 的用例跳过'); admin = null
  } else T('admin 登录成功(旧会话口径)→新token带tv', !!admin.body.data.token)
  const at = admin?.body?.data?.token

  // 3. CRL 判重：连续两次拉取，内容应逐字节一致（不再逢GET自增重签）
  const c1 = await (await fetch(B + '/ca/public/crl')).arrayBuffer()
  const c2 = await (await fetch(B + '/ca/public/crl')).arrayBuffer()
  const eq = Buffer.compare(Buffer.from(c1), Buffer.from(c2)) === 0
  T('GET /ca/public/crl 两次内容一致(编号不逢拉取自增)', eq, 'bytes ' + c1.byteLength + '/' + c2.byteLength)

  // 4. 未登录 401
  const noAuth = await j(B + '/admin/users')
  T('无token访问 /admin/users → 401', noAuth.status === 401, 'HTTP ' + noAuth.status)

  if (at) {
    // 5. ADMIN 查审计应 403（审计收口 AUDITOR）
    const a1 = await j(B + '/admin/audit?pageNo=1&pageSize=5', { headers: H(at) })
    T('ADMIN GET /admin/audit → 403', a1.status === 403, 'HTTP ' + a1.status + ' msg=' + a1.body?.message)

    // 6. 建 AUDITOR 冒烟账号（必须传口令，响应不回显口令）
    const mk = await j(B + '/admin/users', { method: 'POST', headers: H(at), body: JSON.stringify({ username: 'smoke_b_aud', realName: 'B路线审计冒烟', roleCode: 'AUDITOR', password: 'Smk20268x' }) })
    T('建号 smoke_b_aud 成功且响应无口令回显', mk.status === 200 && !JSON.stringify(mk.body).includes('Smk20268x'), JSON.stringify(mk.body?.message))
    // 6b. 弱口令建号应 400
    const weak = await j(B + '/admin/users', { method: 'POST', headers: H(at), body: JSON.stringify({ username: 'smoke_b_weak', realName: '弱口令', roleCode: 'OPERATOR', password: '123456' }) })
    T('弱口令建号被拒(复杂度)', weak.status === 400, 'HTTP ' + weak.status)

    // 7. 审计角色链路
    let au = await login('smoke_b_aud', 'Smk20268x')
    const aut = au.body?.data?.token
    T('smoke_b_aud 登录', !!aut)
    if (aut) {
      const list = await j(B + '/admin/audit?pageNo=1&pageSize=5', { headers: H(aut) })
      T('AUDITOR GET /admin/audit → 200', list.status === 200)
      const cv = await j(B + '/admin/audit/chain-verify', { headers: H(aut) })
      T('AUDITOR 链对账可用', cv.status === 200, 'checked=' + cv.body?.data?.checked + ' broken=' + JSON.stringify(cv.body?.data?.broken?.slice(0, 3)))
      const kp = await j(B + '/admin/certs/1/key.pem', { headers: H(aut) })
      T('AUDITOR 下载私钥 → 403', kp.status === 403, 'HTTP ' + kp.status)
      // 8. 自助改密 + 旧token吊销
      const pkk = await pubKey()
      const cp = await j(B + '/auth/changePassword', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + aut }, body: JSON.stringify({ oldPassword: enc(pkk, 'Smk20268x'), newPassword: enc(pkk, 'Smk20268y') }) })
      T('自助改密成功', cp.body?.code === 200, JSON.stringify(cp.body?.message))
      const oldTok = await j(B + '/admin/audit?pageNo=1&pageSize=1', { headers: H(aut) })
      T('改密后旧token即刻401(token_version吊销)', oldTok.status === 401, 'HTTP ' + oldTok.status)
    }

    // 9. 登录失败锁定（专用账号，勿碰 admin）
    await j(B + '/admin/users', { method: 'POST', headers: H(at), body: JSON.stringify({ username: 'smoke_b_lock', realName: '锁定冒烟', roleCode: 'OPERATOR', password: 'Smk20268z' }) })
    for (let i = 0; i < 5; i++) await login('smoke_b_lock', 'WrongPwd99')
    const locked = await login('smoke_b_lock', 'Smk20268z')
    T('5次失败后正确口令也被锁(15分钟)', locked.body?.code !== 200 && String(locked.body?.message).includes('锁定'), JSON.stringify(locked.body?.message))

    // 10. RA 申请单权限：AUDITOR 提交申请应 403（需 OPERATOR/ADMIN）
    const tpl = await j(B + '/admin/templates', { headers: H(at) })
    const t0 = (tpl.body?.data || []).find?.(x => x.certType !== 'SUB_CA') || tpl.body?.data?.[0]
    if (aut && t0) {
      const sub = await j(B + '/admin/applications', { method: 'POST', headers: H(aut), body: JSON.stringify({ applicantType: 'PERSON', applicantName: '冒烟', subjectDn: 'CN=smoke-b,OU=test', templateId: t0.id }) })
      T('AUDITOR 提交RA申请 → 403', sub.status === 403, 'HTTP ' + sub.status)
    }

    // 收尾：停用冒烟账号（admin 保留给测试）
    const users = await j(B + '/admin/users', { headers: H(at) })
    for (const u of (users.body?.data?.records || users.body?.data || [])) {
      if (String(u.username).startsWith('smoke_b_')) await j(B + '/admin/users/' + u.id, { method: 'POST', headers: H(at), body: JSON.stringify({ status: 0 }) })
    }
    console.log('冒烟账号已停用')
  }
  const fail = results.filter(r => !r.pass).length
  console.log('==== 汇总: ' + (results.length - fail) + '/' + results.length + ' PASS ====')
})().catch(e => { console.error('SMOKE ERROR', e); process.exit(1) })
