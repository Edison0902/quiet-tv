package home.quiet.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** 桌面侧轻舟接收器保活（用户裁定：adb 自动拉起策略绑定到桌面应用）。
 *
 * 桌面常驻前台——由它发起的 startForegroundService 豁免 MIUI 后台 FGS
 * 限制（实测 Background started FGS: Allowed），且启动 Service 不触发
 * "后台弹出界面"横幅（该横幅仅来自 Activity 后台启动，此前 6095 复苏
 * 路径拉起接收器 Activity 的黑屏/横幅即此来源）。
 *
 * 每 45s 拉起轻舟接收器 CastService（8940 投屏通道恢复）。
 * 不恢复 ADB_ENABLED——MIUI 重启清受信主机列表，自动拉起 adbd 只会
 * 每次开机弹 RSA 授权框（用户裁定：弹窗必须杜绝）。 */
final class CastboxLink {
    private static final String PKG = "com.wujiaqi.castbox";
    private static final String SVC = "com.wujiaqi.tvbrowser.cast.CastService";
    private static final long PERIOD_MS = 45000;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean running;

    static void start(Context c) {
        if (running) return;
        running = true;
        final Context app = c.getApplicationContext();
        H.postDelayed(new Runnable() {
            @Override public void run() {
                tick(app);
                if (running) H.postDelayed(this, PERIOD_MS);
            }
        }, 3000);
    }

    static void tick(Context app) {
        // 不恢复 ADB_ENABLED:MIUI 重启会清受信主机列表,adbd 一旦被拉起,
        // relay keeper 连接即触发 RSA 授权弹窗(且 30s 循环反复弹=风暴)。
        // adb 只在用户手动 toggle 时启用;投屏不依赖 adb(8940 由本类保活)。
        try {
            app.startForegroundService(new Intent().setComponent(
                    new ComponentName(PKG, SVC)));
        } catch (Exception e) {
            DiagnosticLog.error("castbox_link.service", e);
        }
    }
}
