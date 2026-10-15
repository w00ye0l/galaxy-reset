package com.forholiday.galaxyreset;

import android.content.Context;
import android.graphics.drawable.Drawable;

/** Official Material Symbols Rounded SVGs, imported as native Android vectors. */
final class UiIcon {
    static final int REFRESH=0, CHECK=1, ALERT=2, CLOSE=3, POWER=4, WIFI=5, PHONE=6,
        SHIELD=7, INFO=8, ARROW=9, HOME=10, MENU=11, DELETE=12;
    private static final int[] RESOURCES={R.drawable.ms_refresh,R.drawable.ms_check,
        R.drawable.ms_warning,R.drawable.ms_close,R.drawable.ms_power_settings_new,
        R.drawable.ms_wifi,R.drawable.ms_smartphone,R.drawable.ms_verified_user,
        R.drawable.ms_info,R.drawable.ms_chevron_right,R.drawable.ms_home,
        R.drawable.ms_menu,R.drawable.ms_delete};
    static Drawable create(Context context,int kind,int color,int pixels){
        Drawable icon=context.getDrawable(RESOURCES[kind]).mutate();
        icon.setTint(color);icon.setBounds(0,0,pixels,pixels);return icon;
    }
}
