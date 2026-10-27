/**
 * 文本内容下载为文件（证书/私钥/CRL 的 .pem 下载）。
 * @param {string} content 文件内容
 * @param {string} filename 文件名（如 cert.pem）
 */
export function downloadText(content, filename) {
  const blob = new Blob([content], { type: 'application/x-pem-file;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
