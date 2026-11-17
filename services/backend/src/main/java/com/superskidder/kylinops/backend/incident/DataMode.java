package com.superskidder.kylinops.backend.incident;

import java.util.Locale;

/**
 * 事件数据分层：三者不混写，UI 必须标明。
 * SYNTHETIC 可精确 reset 的合成输入；REPLAY 真实脱敏时序回放；LIVE 受控实验节点真机信号。
 */
public enum DataMode {
    SYNTHETIC,
    REPLAY,
    LIVE;

    public static DataMode normalize(String value) {
        if (value == null || value.isBlank()) {
            return SYNTHETIC;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SYNTHETIC;
        }
    }
}
