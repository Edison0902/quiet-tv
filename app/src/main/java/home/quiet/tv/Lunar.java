package home.quiet.tv;

import java.util.Calendar;
import java.util.TimeZone;

/** Lunar calendar conversion, ported from lanshare's zhdate-derived implementation (1900-2100). */
final class Lunar {
    private static final String[] MONTHS_ZH={"正月","二月","三月","四月","五月","六月","七月","八月","九月","十月","冬月","腊月"};
    private Lunar(){}
    private static long newYear(int year){
        String code=LunarTables.NEW_YEAR[year-1900];
        return epochDay(Integer.parseInt(code.substring(0,4)),Integer.parseInt(code.substring(4,6)),Integer.parseInt(code.substring(6,8)));
    }
    static long epochDay(int y,int m,int d){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.clear();c.set(y,m-1,d);
        return c.getTimeInMillis()/86400000L;
    }
    static int civilYear(long epochDay){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.clear();c.setTimeInMillis(epochDay*86400000L);
        return c.get(Calendar.YEAR);
    }
    /** {month number, leap flag, day count} for every lunar month of the year. */
    private static int[][] months(int year){
        int code=LunarTables.YEAR_CODE[year-1900],leap=code&0xF;
        int[] days=new int[13];int n=0;
        for(int b=15;b>=4;b--)days[n++]=((code>>b)&1)!=0?30:29;
        if(leap!=0){
            for(int j=n;j>leap;j--)days[j]=days[j-1];
            days[leap]=(code>>16)!=0?30:29;n++;
        }
        int[][] out=new int[n][3];int m=1;
        for(int i=0;i<n;i++){
            boolean isl=leap!=0&&i==leap;
            out[i][0]=m;out[i][1]=isl?1:0;out[i][2]=days[i];
            if(!isl)m++;
        }
        return out;
    }
    /** A flat-month lunar date to its solar date that year; null when the day does not exist (廿九 without a 三十). */
    static int[] lunarToSolar(int year,int month,int day){
        if(year<1900||year>2099||month<1||month>12||day<1||day>30)return null;
        long start=newYear(year);int off=0;
        for(int[] pm:months(year)){
            if(pm[0]==month&&pm[1]==0){
                if(day>pm[2])return null;
                return civil(start+off+day-1);
            }
            off+=pm[2];
        }
        return null;
    }
    /** A solar date to {lunar year, month, leap flag, day}; null out of range. */
    static int[] solarToLunar(int y,int m,int d){
        if(y<1901||y>2100)return null;
        long day=epochDay(y,m,d),ny=newYear(y);
        if(day<ny){
            y-=1;if(y<1900)return null;ny=newYear(y);
        }
        int off=(int)(day-ny);
        for(int[] pm:months(y)){
            if(off<pm[2])return new int[]{y,pm[0],pm[1],off+1};
            off-=pm[2];
        }
        return null;
    }
    /** 农历九月初二 / 闰四月十五 style description. */
    static String zh(int m,int leap,int d){
        String nums="一二三四五六七八九十",ds;
        if(d==10)ds="初十";else if(d==20)ds="二十";else if(d==30)ds="三十";
        else if(d<10)ds="初"+nums.charAt(d-1);
        else if(d<20)ds="十"+nums.charAt(d-11);
        else ds="廿"+nums.charAt(d-21);
        return "农历"+(leap!=0?"闰":"")+MONTHS_ZH[m-1]+ds;
    }
    static int[] civil(long epochDay){
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.clear();c.setTimeInMillis(epochDay*86400000L);
        return new int[]{c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH)};
    }
}
