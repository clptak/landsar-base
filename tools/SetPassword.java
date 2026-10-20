import java.sql.*;
import java.util.Scanner;
import org.apache.shiro.authc.credential.DefaultPasswordService;

/** Sets a LandSAR user's password. Run inside the container; reads the new password from stdin.
 *  docker compose exec -T landsar java -cp "/opt/landsar/tools-bin:/opt/landsar/dependencies/*" SetPassword admin */
public class SetPassword {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) { System.err.println("usage: SetPassword <username>  (new password on stdin)"); System.exit(2); }
        String pw = new Scanner(System.in).nextLine();
        if (pw.length() < 12) { System.err.println("refusing: use at least 12 characters"); System.exit(2); }
        DefaultPasswordService svc = new DefaultPasswordService();
        try (Connection c = DriverManager.getConnection("jdbc:derby://127.0.0.1:1621/landsar")) {
            try (PreparedStatement u = c.prepareStatement("update users set password_hash = ? where username = ?")) {
                u.setString(1, svc.encryptPassword(pw));
                u.setString(2, args[0]);
                if (u.executeUpdate() != 1) { System.err.println("no such user: " + args[0]); System.exit(1); }
            }
            try (PreparedStatement q = c.prepareStatement("select password_hash from users where username = ?")) {
                q.setString(1, args[0]);
                ResultSet r = q.executeQuery();
                if (!r.next() || !svc.passwordsMatch(pw, r.getString(1))) { System.err.println("verify FAILED"); System.exit(1); }
            }
        }
        System.out.println("password updated and verified for " + args[0]);
    }
}
