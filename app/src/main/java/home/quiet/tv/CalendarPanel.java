package home.quiet.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.*;

/** The calendar card: this month's grid with event dots, beside the upcoming birthdays. */
final class CalendarPanel {
    private final Context context;
    private final float unit;
    private final boolean chinese;
    private final LinearLayout card,rows;
    private final MonthGridView grid;
    private final TextView empty;
    private long today;
    CalendarPanel(Context context,float unit,boolean chinese){
        this.context=context;this.unit=unit;this.chinese=chinese;
        today=todayEpoch();
        int[] civil=Lunar.civil(today);
        card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(14*unit);card.setPadding(pad,pad,pad,Math.round(10*unit));
        LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lunarTitle=text(Lunar.zh(civil[1],0,civil[2]),14,600,0xfff1f2f6);
        head.addView(lunarTitle,new LinearLayout.LayoutParams(0,-2,1));
        head.addView(text(monthTitle(civil[0],civil[1]),12,400,0xffb0b4be),new LinearLayout.LayoutParams(-2,-2));
        card.addView(head,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout columns=new LinearLayout(context);
        grid=new MonthGridView(context,unit,chinese,civil[0],civil[1],today);
        columns.addView(grid,new LinearLayout.LayoutParams(Math.round(352*unit),Math.round(212*unit)));
        LinearLayout side=new LinearLayout(context);side.setOrientation(LinearLayout.VERTICAL);side.setPadding(Math.round(14*unit),Math.round(2*unit),0,0);
        rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        side.addView(rows,new LinearLayout.LayoutParams(-1,-2));
        empty=text(tr("暂无生日记录","No birthdays yet"),11,400,0x80b0b4be);
        side.addView(empty,new LinearLayout.LayoutParams(-1,-2));
        columns.addView(side,new LinearLayout.LayoutParams(0,-2,1));
        card.addView(columns,new LinearLayout.LayoutParams(-1,-2));
        setEntries(Collections.emptyList());
    }
    View card(){return card;}
    void setToday(){today=todayEpoch();}
    void setEntries(List<Birthday> entries){
        Set<Long> events=new HashSet<>();
        List<EntryLabel> labels=new ArrayList<>();
        for(Birthday entry:entries){
            Birthday.Occurrence occurrence=entry.next(today);
            if(occurrence==null)continue;
            events.add(occurrence.epoch);
            labels.add(new EntryLabel(entry,occurrence));
        }
        labels.sort(Comparator.comparingLong(l->l.occurrence.epoch));
        grid.setEvents(events);
        rows.removeAllViews();
        empty.setVisibility(labels.isEmpty()?View.VISIBLE:View.GONE);
        for(int i=0;i<Math.min(5,labels.size());i++)rows.addView(row(labels.get(i)),new LinearLayout.LayoutParams(-1,-2));
        if(labels.size()>5)rows.addView(more(labels.size()-5),new LinearLayout.LayoutParams(-1,-2));
    }
    private static final class EntryLabel{
        final Birthday entry;final Birthday.Occurrence occurrence;
        EntryLabel(Birthday entry,Birthday.Occurrence occurrence){this.entry=entry;this.occurrence=occurrence;}
    }
    private View row(EntryLabel label){
        Birthday.Occurrence o=label.occurrence;
        int days=o.daysUntil(today);
        String when=days==0?tr("今天","Today"):days==1?tr("明天","Tomorrow"):days+tr(" 天后"," days");
        String detail=o.lunar.isEmpty()
            ?o.month+"-"+o.day+(label.entry.type.equals("lunar")?tr(" 农历"," lunar"):"")
            :o.lunar+(label.entry.type.equals("solar")?"":"");
        int accent=days<=7?0xffffa26b:0xffe6e7ed;
        LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0,Math.round(6*unit),0,Math.round(6*unit));
        LinearLayout line=new LinearLayout(context);line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text(label.entry.name,13,500,0xfff1f2f6);
        TextView countdown=text(when,11,500,accent);
        line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        line.addView(countdown,new LinearLayout.LayoutParams(-2,-2));
        row.addView(line,new LinearLayout.LayoutParams(-1,-2));
        TextView sub=text(detail+dateSuffix(o)+label.occurrence.ageText(),10,400,0x8fb0b4be);
        sub.setPadding(0,Math.round(2*unit),0,0);
        row.addView(sub,new LinearLayout.LayoutParams(-1,-2));
        return row;
    }
    private String dateSuffix(Birthday.Occurrence o){return " · "+o.month+tr("月","/")+(o.day<10?"0":"")+o.day+tr("日","");}
    private String monthTitle(int year,int month){
        return chinese?year+"年"+month+"月":String.format(java.util.Locale.US,"%d-%02d",year,month);
    }
    private TextView more(int count){TextView t=text("+"+count,11,400,0x80b0b4be);t.setPadding(0,Math.round(4*unit),0,0);return t;}
    private long todayEpoch(){
        Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        return Lunar.epochDay(c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));
    }
    private String tr(String zh,String en){return chinese?zh:en;}
    private TextView text(String value,int size,int weight,int color){
        TextView v=new TextView(context);v.setText(value);v.setTextColor(color);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,Math.round(size*unit));
        v.setTypeface(android.graphics.Typeface.create("sans-serif",weight>=600?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL));
        v.setIncludeFontPadding(false);v.setSingleLine(true);return v;
    }
    /** The month grid: 一-日 header, day numbers, a filled circle on today, dots on days with events. */
    private static final class MonthGridView extends View {
        private final float unit;private final boolean chinese;
        private final int year,month;private final long today;
        private Set<Long> events=Collections.emptySet();
        private final Paint number=new Paint(Paint.ANTI_ALIAS_FLAG),todayFill=new Paint(Paint.ANTI_ALIAS_FLAG),
            dot=new Paint(Paint.ANTI_ALIAS_FLAG),weekday=new Paint(Paint.ANTI_ALIAS_FLAG);
        MonthGridView(Context c,float unit,boolean chinese,int year,int month,long today){
            super(c);this.unit=unit;this.chinese=chinese;this.year=year;this.month=month;this.today=today;
            number.setTextAlign(Paint.Align.CENTER);weekday.setTextAlign(Paint.Align.CENTER);
            number.setTextSize(11*unit);number.setColor(0xF2FFFFFF);
            weekday.setTextSize(9.5f*unit);weekday.setColor(0x73FFFFFF);
            todayFill.setStyle(Paint.Style.FILL);todayFill.setColor(0xF2FFFFFF);
            dot.setStyle(Paint.Style.FILL);dot.setColor(0x8CFFFFFF);
            setFocusable(false);
        }
        void setEvents(Set<Long> events){this.events=events;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float w=getWidth(),h=getHeight(),cellW=w/7f,top=10*unit,rowH=(h-top-4*unit)/6f;
            String[] wd=chinese?new String[]{"一","二","三","四","五","六","日"}:new String[]{"M","T","W","T","F","S","S"};
            for(int i=0;i<7;i++)canvas.drawText(wd[i],cellW*(i+.5f),top+2*unit,weekday);
            Calendar first=Calendar.getInstance();first.clear();first.set(year,month-1,1);
            int offset=(first.get(Calendar.DAY_OF_WEEK)+5)%7; // Monday-first column
            int count=first.getActualMaximum(Calendar.DAY_OF_MONTH);
            for(int d=1;d<=count;d++){
                int position=offset+d-1,row=position/7,col=position%7;
                float cx=cellW*(col+.5f),cy=top+rowH*(row+.42f);
                long epoch=Lunar.epochDay(year,month,d);
                if(epoch==today){
                    canvas.drawCircle(cx,cy-3*unit,10.5f*unit,todayFill);
                    number.setColor(0xff20232b);
                }
                canvas.drawText(String.valueOf(d),cx,cy,number);
                if(epoch==today)number.setColor(0xF2FFFFFF);
                if(events.contains(epoch))canvas.drawCircle(cx,cy+7.5f*unit,1.8f*unit,dot);
            }
        }
    }
}
