package home.quiet.tv;

import org.json.JSONException;
import org.json.JSONObject;

/** A birthday or anniversary entry, with lanshare's next-occurrence rules. */
final class Birthday {
    final String name,note,type;
    final int month,day,birthYear; // birthYear 0 = unknown
    Birthday(JSONObject o)throws JSONException{
        name=o.getString("name");type=o.optString("type","solar");
        month=o.optInt("month",0);day=o.optInt("day",0);
        birthYear=o.optInt("birthYear",0);note=o.optString("note","");
    }
    /** The entry's next occurrence at or after today (epoch day), with its solar date and lunar text. */
    Occurrence next(long today){
        int from=Lunar.civilYear(today);
        for(int y=from;y<from+9;y++){
            int[] solar=type.equals("lunar")?Lunar.lunarToSolar(y,month,day):solarDate(y,month,day);
            if(solar==null)continue; // 该年此日不存在,顺延下一年 (lanshare's roll-forward)
            long epoch=Lunar.epochDay(solar[0],solar[1],solar[2]);
            if(epoch>=today)return new Occurrence(epoch,solar,birthYear);
        }
        return null;
    }
    private int[] solarDate(int y,int m,int d){
        if(m<1||m>12||d<1)return null;
        int dim=m==2?(y%4==0&&(y%100!=0||y%400==0)?29:28):m==4||m==6||m==9||m==11?30:31;
        return d>dim?null:new int[]{y,m,d};
    }
    /** One resolved occurrence. */
    static final class Occurrence {
        final long epoch;final int year,month,day,birthYear;final String lunar;
        Occurrence(long epoch,int[] solar,int birthYear){
            this.epoch=epoch;this.birthYear=birthYear;year=solar[0];month=solar[1];day=solar[2];
            int[] lunar=Lunar.solarToLunar(solar[0],solar[1],solar[2]);
            this.lunar=lunar==null?"":Lunar.zh(lunar[1],lunar[2],lunar[3]);
        }
        int daysUntil(long today){return (int)(epoch-today);}
        String ageText(){
            if(birthYear<=0)return "";
            int age=year-birthYear;
            return age>0?" · "+age+"周岁":"";
        }
    }
}
