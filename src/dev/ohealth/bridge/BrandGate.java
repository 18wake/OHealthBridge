package dev.ohealth.bridge;

import android.os.Build;

import java.util.Locale;

/**
 * 品牌网关: OPPO 系手机原生支持这些联动(走 ColorOS 私有 API), 模块应闲置避免干扰;
 * 其余品牌(小米/荣耀/三星/pixel 等)启用模块桥接。
 */
public class BrandGate {

    private static final String[] OPLUS_FAMILY = {"oppo", "oneplus", "realme", "oplus"};

    public final String mode;

    private BrandGate(String mode) {
        this.mode = mode;
    }

    public static BrandGate fromConfig() {
        return new BrandGate(Config.get().brandMode);
    }

    public boolean enabledHere() {
        if ("force".equals(mode)) {
            return true;
        }
        String mfr = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
        for (String o : OPLUS_FAMILY) {
            if (mfr.contains(o) || brand.contains(o)) {
                return false;
            }
        }
        return true;
    }
}
