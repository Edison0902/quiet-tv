package home.quiet.tv;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Birthday entries: lanshare's API when the LAN allows it, the bundled snapshot otherwise. */
final class BirthdayStore {
    static final String LANSHARE="http://192.168.77.200:8000"; // PVE host; the TV's subnet is allowed on port 8000
    private static final long REFRESH_MS=30*60*1000L;
    private static BirthdayStore instance;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<Birthday> entries=new ArrayList<>();
    private final List<Listener> listeners=new ArrayList<>();
    private long fetchedAt;
    private boolean fetchedFromServer,bundledLoaded;
    interface Listener{void changed();}
    static synchronized BirthdayStore get(Context context){
        if(instance==null)instance=new BirthdayStore(context.getApplicationContext());return instance;
    }
    private BirthdayStore(Context context){this.context=context.getApplicationContext();}
    private final Context context;
    synchronized void addListener(Listener listener){if(!listeners.contains(listener))listeners.add(listener);}
    synchronized void removeListener(Listener listener){listeners.remove(listener);}
    synchronized List<Birthday> snapshot(){return new ArrayList<>(entries);}
    synchronized boolean fromServer(){return fetchedFromServer;}
    /** Refresh in the background; listeners fire once the list is ready (server or bundled). */
    void refresh(){
        worker.submit(()->{
            List<Birthday> result=fetchServer();
            boolean live=result!=null;
            if(!live)result=loadBundled();
            synchronized(this){
                if(result==null)return;
                entries.clear();entries.addAll(result);
                if(live){fetchedFromServer=true;fetchedAt=System.currentTimeMillis();}
                if(!bundledLoaded)bundledLoaded=true;
            }
            Log.i("QuietTV","Birthdays ready live="+live+" count="+result.size());
            main.post(()->{List<Listener> targets; synchronized(this){targets=new ArrayList<>(listeners);} for(Listener l:targets)l.changed();});
        });
    }
    private List<Birthday> fetchServer(){
        try{
            HttpURLConnection request=(HttpURLConnection)new URL(LANSHARE+"/api/birthdays").openConnection();
            request.setConnectTimeout(4000);request.setReadTimeout(4000);
            request.setRequestProperty("Accept","application/json");
            int status=request.getResponseCode();
            if(status!=200){request.disconnect();return null;}
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream input=request.getInputStream()){
                byte[] chunk=new byte[4096];int n;while((n=input.read(chunk))>0)buffer.write(chunk,0,n);
            }
            JSONArray items=new JSONObject(buffer.toString("UTF-8")).getJSONArray("items");
            List<Birthday> result=new ArrayList<>();
            for(int i=0;i<items.length();i++)result.add(new Birthday(items.getJSONObject(i)));
            return result;
        }catch(IOException|JSONException|RuntimeException e){
            DiagnosticLog.info("birthday.fetch.failed","kind="+e.getClass().getSimpleName());
            return null;
        }
    }
    private List<Birthday> loadBundled(){
        try{
            InputStream input=context.getAssets().open("birthdays.json");ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            byte[] chunk=new byte[4096];int n;while((n=input.read(chunk))>0)buffer.write(chunk,0,n);
            JSONArray items=new JSONArray(buffer.toString("UTF-8"));
            List<Birthday> result=new ArrayList<>();
            for(int i=0;i<items.length();i++)result.add(new Birthday(items.getJSONObject(i)));
            return result;
        }catch(IOException|JSONException e){
            Log.w("QuietTV","Bundled birthdays unavailable: "+e);
            return new ArrayList<>();
        }
    }
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
}
