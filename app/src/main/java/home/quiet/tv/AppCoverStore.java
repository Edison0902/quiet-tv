package home.quiet.tv;

import android.content.Context;
import androidx.core.util.AtomicFile;
import android.util.Log;
import java.io.*;
import home.quiet.tv.Charsets;
import java.util.UUID;
import org.json.*;

/** One current cover per package, scoped by Android's private per-user storage. */
final class AppCoverStore {
    static final long MAX_UPLOAD=2L*1024*1024;
    private static AppCoverStore instance;
    private final File directory;
    private final AtomicFile indexFile;
    private JSONObject index;
    static synchronized AppCoverStore get(Context context){
        if(instance==null)instance=new AppCoverStore(context.getApplicationContext());return instance;
    }
    private AppCoverStore(Context context){
        directory=new File(context.getFilesDir(),"app-covers");
        indexFile=new AtomicFile(new File(directory,"index.json"));index=new JSONObject();
        if(indexFile.getBaseFile().exists()||new File(directory,"index.json.bak").exists())try{
            index=new JSONObject(new String(indexFile.readFully(),Charsets.UTF_8));
        }catch(IOException|JSONException e){Log.w("QuietTV","Cover index unavailable; using original artwork");}
        importBundled(context);
    }
    /** Fork: a bundled hi-res cover in assets/app-covers/<pkg>.png replaces the blurry stock banner.
     *  A changed stamp.txt re-imports bundled covers over existing ones, so shipping new art reaches
     *  installed devices on a plain update without pm clear (which would drop user settings). */
    private void importBundled(Context context){
        try{
            boolean force=false;
            try{String stamp=new String(readAsset(context,"app-covers/stamp.txt"),Charsets.UTF_8).trim();
                if(!stamp.isEmpty()&&!stamp.equals(index.optString("coverStamp"))){index.put("coverStamp",stamp);force=true;}
            }catch(IOException|JSONException missing){/* no stamp file: keep upstream behaviour */}
            for(String name:context.getAssets().list("app-covers")){
                if(!name.endsWith(".png"))continue;
                String pkg=name.substring(0,name.length()-4);
                if(!packageName(pkg)||(!force&&index.has(pkg)))continue;
                File ready=prepared();
                try(InputStream input=context.getAssets().open("app-covers/"+name);OutputStream output=new FileOutputStream(ready)){
                    byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))>0)output.write(buffer,0,n);
                }
                apply(pkg,ready);
            }
        }catch(IOException e){Log.w("QuietTV","Bundled covers unavailable: "+e);}
    }
    static boolean packageName(String pkg){return pkg!=null&&pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");}
    private static byte[] readAsset(Context context,String name) throws IOException {
        InputStream input=context.getAssets().open(name);ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try{byte[] chunk=new byte[256];int n;while((n=input.read(chunk))>0)buffer.write(chunk,0,n);}finally{input.close();}
        return buffer.toByteArray();
    }
    private void ensureDirectory() throws IOException {
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法保存封面，请检查电视存储空间 / Cover storage unavailable");
    }
    File incoming() throws IOException {ensureDirectory();return File.createTempFile("incoming-",".tmp",directory);}
    File prepared() throws IOException {ensureDirectory();return new File(directory,"cover-"+UUID.randomUUID()+".png");}
    private File image(JSONObject entry){
        String name=entry==null?"":entry.optString("file");
        return name.matches("cover-[a-f0-9-]{36}\\.png")?new File(directory,name):null;
    }
    synchronized File file(String pkg){File f=image(index.optJSONObject(pkg));return f!=null&&f.isFile()?f:null;}
    synchronized boolean custom(String pkg){return file(pkg)!=null;}
    synchronized String key(String pkg){File f=file(pkg);return f==null?"original":f.getName();}
    synchronized long revision(){return index.optLong("revision",0);}
    private void persist(JSONObject next) throws IOException {
        ensureDirectory();FileOutputStream out=null;
        try{out=indexFile.startWrite();out.write(next.toString().getBytes(Charsets.UTF_8));indexFile.finishWrite(out);index=next;}
        catch(IOException e){if(out!=null)indexFile.failWrite(out);throw e;}
    }
    synchronized void apply(String pkg,File ready) throws IOException {
        if(!packageName(pkg)||!directory.getCanonicalFile().equals(ready.getCanonicalFile().getParentFile())
                ||!ready.getName().matches("cover-[a-f0-9-]{36}\\.png")||ready.length()==0||ready.length()>MAX_UPLOAD)
            throw new IOException("无效的应用封面 / Invalid cover");
        File old=image(index.optJSONObject(pkg));
        try{JSONObject next=new JSONObject(index.toString());next.put(pkg,new JSONObject().put("file",ready.getName()).put("width",720).put("height",406));
            next.put("revision",revision()+1);persist(next);
        }catch(JSONException e){throw new IOException("Unable to save cover settings",e);}
        if(old!=null&&!old.equals(ready))discard(old);
    }
    synchronized void restore(String pkg) throws IOException {
        if(!index.has(pkg))return;File old=image(index.optJSONObject(pkg));
        try{JSONObject next=new JSONObject(index.toString());next.remove(pkg);next.put("revision",revision()+1);persist(next);}
        catch(JSONException e){throw new IOException("Unable to restore cover",e);}
        discard(old);
    }
    static void discard(File file){if(file!=null&&file.exists()&&!file.delete())Log.w("QuietTV","Cover file retained");}
}
