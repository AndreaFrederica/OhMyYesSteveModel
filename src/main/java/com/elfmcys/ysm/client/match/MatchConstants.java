package com.elfmcys.ysm.client.match;

public final class MatchConstants {
    private MatchConstants() {
    }

    /// 代表继续下一个测试；如果作为返回结果，外部不会播放该动画
    public static final String EMPTY = "";

    public static final char ID_PRE_C = '$';
    public static final String ID_PRE = String.valueOf(ID_PRE_C);
    public static final char ORE_PRE_C = '~';
    public static final String ORE_PRE = String.valueOf(ORE_PRE_C);
    public static final char TAG_PRE_C = '#';
    public static final String TAG_PRE = String.valueOf(TAG_PRE_C);
    public static final char EXTRA_PRE_C = ':';
    public static final String EXTRA_PRE = String.valueOf(EXTRA_PRE_C);
}
