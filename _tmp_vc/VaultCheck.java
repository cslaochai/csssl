import cn.hutool.crypto.symmetric.SM4;
import cn.hutool.core.util.HexUtil;
import java.nio.file.*;
import java.sql.*;

public class VaultCheck {
    public static void main(String[] a) throws Exception {
        String masterKey = Files.readString(Paths.get(a[0])).trim();
        System.out.println("masterKey.head=" + masterKey.substring(0, Math.min(16, masterKey.length())) + " len=" + masterKey.length());
        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception e) { System.out.println("driver load fail: " + e); }
        String url = "jdbc:mysql://192.168.88.132:3306/ssl_ca?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(url, "xz_feng", "Feng123456!")) {
            String sql = "SELECT company_uscc, rel_dir, name, cipher_hex, sealed FROM ssl_key_vault WHERE rel_dir=? AND name=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, a[1]);
                ps.setString(2, a[2]);
                try (ResultSet rs = ps.executeQuery()) {
                    int n = 0;
                    while (rs.next()) {
                        n++;
                        String cipher = rs.getString("cipher_hex");
                        System.out.println("---- row #" + n + " ----");
                        System.out.println("companyUscc=" + rs.getString("company_uscc") + " relDir=" + rs.getString("rel_dir") + " name=" + rs.getString("name") + " sealed=" + rs.getInt("sealed"));
                        System.out.println("cipherHex.len=" + cipher.length() + " head=" + cipher.substring(0, Math.min(32, cipher.length())));
                        SM4 sm4 = new SM4(HexUtil.decodeHex(masterKey));
                        try {
                            String dec = sm4.decryptStr(cipher.trim());
                            System.out.println("DECRYPTED=[" + dec + "]");
                            System.out.println("MATCH_HIS_SECRET=" + dec.equals("sk_6839f7424eae7cd4405e323564cf7511cc52fe74268e87c4f6f0722e82d5cd52"));
                        } catch (Exception e) {
                            System.out.println("DECRYPT_FAILED: " + e);
                        }
                    }
                    if (n == 0) System.out.println("NO ROW for rel_dir=" + a[1] + " name=" + a[2]);
                }
            }
        }
    }
}
