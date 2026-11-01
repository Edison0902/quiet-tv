package home.quiet.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * The next 24 hours under the header, in the spirit of the Apple Weather card: a smooth temperature
 * line with a fade below, the current hour marked with a filled dot, the window's low and high with
 * hollow ones, faint columns for the chance of precipitation, and the city's hours along the
 * bottom. Pure drawing: nothing animates, nothing runs while Home sits idle.
 */
final class WeatherCurveView extends View {
    private long[] at;
    private float[] temps;
    private int[] pop;      // precipitation probability 0-100, -1 unknown
    private int n,minI=-1,maxI=-1;
    private int[] tickI;
    private String[] tickText;
    private final float unit;
    private final String nowLabel,hourFormat;
    private final Paint line=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(Paint.ANTI_ALIAS_FLAG),
        label=new Paint(Paint.ANTI_ALIAS_FLAG),tick=new Paint(Paint.ANTI_ALIAS_FLAG),
        dot=new Paint(Paint.ANTI_ALIAS_FLAG),bar=new Paint(Paint.ANTI_ALIAS_FLAG);
    WeatherCurveView(Context context,float unit,boolean chinese){
        super(context);this.unit=unit;
        nowLabel=chinese?"现在":"Now";hourFormat=chinese?"%d时":"%d:00";
        line.setStyle(Paint.Style.STROKE);line.setStrokeCap(Paint.Cap.ROUND);line.setStrokeJoin(Paint.Join.ROUND);line.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);tick.setTextAlign(Paint.Align.CENTER);bar.setStyle(Paint.Style.FILL);
        // Sizes fixed once: the view is drawn at one logical scale and never reconfigured.
        line.setStrokeWidth(2*unit);label.setTextSize(10.5f*unit);tick.setTextSize(10*unit);
        dot.setStrokeWidth(1.6f*unit);
        label.setColor(0xF2FFFFFF);tick.setColor(0x80FFFFFF);dot.setColor(0xFFFFFFFF);bar.setColor(0x59FFFFFF);
        setFocusable(false);
    }
    boolean hasData(){return n>=3;}
    /** The window arrives already cut to this local hour plus 23 more. */
    void setData(WeatherData.Hourly h){
        if(h==null||h.at.length<3){n=0;invalidate();return;}
        at=h.at;temps=h.temps;pop=h.pop;n=Math.min(24,at.length);
        minI=maxI=-1;
        for(int i=1;i<n;i++){
            if(minI<0||temps[i]<temps[minI])minI=i;
            if(maxI<0||temps[i]>temps[maxI])maxI=i;
        }
        int[] ticks={0,6,12,18};tickI=new int[ticks.length];tickText=new String[ticks.length];
        int k=0;
        for(int t:ticks){
            if(t>=n)break;
            tickI[k]=t;
            tickText[k]=t==0?nowLabel:String.format(java.util.Locale.US,hourFormat,(int)(((at[t]+h.offsetSec)%86400L)/3600L));
            k++;
        }
        invalidate();
    }
    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(n<3)return;
        float w=getWidth(),h=getHeight();
        float pad=7*unit,top=20*unit,bottom=h-15*unit;
        float step=(w-2*pad)/(n-1);
        float lo=temps[0],hi=temps[0];
        for(int i=0;i<n;i++){lo=Math.min(lo,temps[i]);hi=Math.max(hi,temps[i]);}
        float span=Math.max(hi-lo,1f)*1.15f;
        float mid=(lo+hi)/2f;
        float[] xs=new float[n],ys=new float[n];
        for(int i=0;i<n;i++){
            xs[i]=pad+step*i;
            ys[i]=(bottom+top)/2f-(temps[i]-mid)/span*(bottom-top);
        }
        // Line and its fade.
        Path curve=smooth(xs,ys);
        fill.setShader(new LinearGradient(0,top,0,bottom,0x2EFFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP));
        Path area=new Path(curve);
        area.lineTo(xs[n-1],bottom);area.lineTo(xs[0],bottom);area.close();
        canvas.drawPath(area,fill);
        canvas.drawPath(curve,line);
        // Precipitation columns along the curve's floor, above the hours line.
        for(int i=0;i<n;i++){
            if(pop[i]<5)continue;
            float bh=pop[i]/100f*11*unit;
            canvas.drawRect(xs[i]-unit,bottom-bh,xs[i]+unit,bottom,bar);
        }
        // The current hour: a filled dot and its temperature; the low and the high: hollow rings.
        // Marks that would crowd the current hour (it is often also the window's low before dawn)
        // are left out: their number is already on screen.
        canvas.drawCircle(xs[0],ys[0],3.2f*unit,dot);
        drawTemp(canvas,xs[0],ys[0],temps[0],true);
        if(minI>1){dot.setStyle(Paint.Style.STROKE);canvas.drawCircle(xs[minI],ys[minI],2.6f*unit,dot);dot.setStyle(Paint.Style.FILL);drawTemp(canvas,xs[minI],ys[minI],temps[minI],false);}
        if(maxI>1){dot.setStyle(Paint.Style.STROKE);canvas.drawCircle(xs[maxI],ys[maxI],2.6f*unit,dot);dot.setStyle(Paint.Style.FILL);drawTemp(canvas,xs[maxI],ys[maxI],temps[maxI],false);}
        // Hours along the bottom.
        for(int k=0;k<tickI.length&&tickText[k]!=null;k++)
            canvas.drawText(tickText[k],clamp(xs[tickI[k]],tick.measureText(tickText[k])/2f,w-pad),h-3.5f*unit,tick);
    }
    /** The temperature above a point, kept inside the view. */
    private void drawTemp(Canvas canvas,float x,float y,float t,boolean now){
        String s=String.valueOf(Math.round(t))+"°";
        float tw=label.measureText(s);
        canvas.drawText(s,clamp(x,tw/2f,getWidth()-tw/2f-2*unit),y-(now?7f:6.5f)*unit,label);
    }
    private static float clamp(float x,float min,float max){return Math.max(min,Math.min(max,x));}
    /** Catmull-Rom through the points as cubic Béziers: smooth like Apple's, no overshoot at the ends. */
    private Path smooth(float[] xs,float[] ys){
        Path p=new Path();
        p.moveTo(xs[0],ys[0]);
        for(int i=1;i<n;i++){
            float x1=xs[i-1],y1=ys[i-1],x2=xs[i],y2=ys[i];
            float cx1=x1+(x2-xs[Math.max(0,i-2)])/6f;
            float cy1=y1+(y2-ys[Math.max(0,i-2)])/6f;
            float cx2=x2-(xs[Math.min(n-1,i+1)]-x1)/6f;
            float cy2=y2-(ys[Math.min(n-1,i+1)]-y1)/6f;
            p.cubicTo(cx1,cy1,cx2,cy2,x2,y2);
        }
        return p;
    }
}
