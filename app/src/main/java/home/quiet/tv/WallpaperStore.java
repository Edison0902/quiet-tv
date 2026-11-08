package home.quiet.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.core.util.AtomicFile;
import android.util.Log;
import org.json.*;
import java.io.*;
import home.quiet.tv.Charsets;
import java.util.*;

/** One process-wide, atomically saved wallpaper catalog for every native surface. */
final class WallpaperStore {
    private static WallpaperStore instance;
    private final File directory;
    private final AtomicFile catalogFile;
    private final ScreensaverStore screensavers;
    private WallpaperCatalog catalog;
    static synchronized WallpaperStore get(Context context) {
        if(instance==null) instance=new WallpaperStore(context.getApplicationContext());
        return instance;
    }
    private WallpaperStore(Context context) {
        screensavers=ScreensaverStore.get(context);
        directory=new File(context.getFilesDir(),"wallpapers");
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Wallpaper storage unavailable");
        catalogFile=new AtomicFile(new File(directory,"catalog.json"));
        if(catalogFile.getBaseFile().exists() || new File(directory,"catalog.json.bak").exists()) {
            try { catalog=WallpaperCatalog.read(new JSONObject(new String(catalogFile.readFully(),Charsets.UTF_8))); }
            catch(IOException|JSONException e) { Log.w("QuietTV","Wallpaper catalog could not be read");DiagnosticLog.error("wallpaper.catalog.failed",e); catalog=new WallpaperCatalog(); }
        } else {
            catalog=new WallpaperCatalog();
            try { persist(catalog); } catch(IOException e) { Log.w("QuietTV","Wallpaper migration will retry on next launch"); }
        }
        // A missing upload must never leave the home screen pointing at an absent file.
        for(Iterator<WallpaperCatalog.Entry> it=catalog.custom.iterator();it.hasNext();) if(!file(it.next().id).isFile()) it.remove();
        catalog.normalize();
        try{screensavers.initialize(catalog);}catch(IOException e){DiagnosticLog.error("screensaver.load.failed",e);}
        importBundledWallpaper(context);
        importBundledBatch(context);
    }
    /**
     * Fork: on a fresh catalog the bundled landscape (assets/default-wallpaper.jpg) is imported
     * and selected, so a wiped TV still opens on a picture. A stamp re-imports it when the asset
     * changes, but never over a wallpaper the owner has chosen themselves.
     */
    private void importBundledWallpaper(Context context){
        try{
            String stamp;
            try(InputStream input=context.getAssets().open("default-wallpaper.stamp");ByteArrayOutputStream read=new ByteArrayOutputStream()){
                byte[] buffer=new byte[256];int n;while((n=input.read(buffer))>0)read.write(buffer,0,n);
                stamp=read.toString("UTF-8").trim();
            }
            SharedPreferences stampPrefs=context.getSharedPreferences("bundled-wallpaper",0);
            boolean fresh=catalog.custom.isEmpty()&&WallpaperCatalog.DEFAULT.equals(catalog.current);
            boolean changed=!stampPrefs.getString("stamp","").equals(stamp);
            if(!fresh&&!changed)return;
            if(!fresh&&changed&&(catalog.current.startsWith("user-")||WallpaperCatalog.DAILY.equals(catalog.current)))return;
            File stage=new File(directory,"incoming-default.jpg");
            try(InputStream input=context.getAssets().open("default-wallpaper.jpg");OutputStream output=new FileOutputStream(stage)){
                byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))>0)output.write(buffer,0,n);
            }
            android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(stage.getPath(),options);
            WallpaperCatalog.Entry entry=add(stage,"风景",options.outWidth,options.outHeight);
            select(entry.id);
            stampPrefs.edit().putString("stamp",stamp).apply();
            Log.i("QuietTV","Bundled wallpaper imported: "+entry.id);
        }catch(IOException e){Log.w("QuietTV","Bundled wallpaper unavailable: "+e);}
    }
    synchronized WallpaperCatalog snapshot() { return catalog.copy(); }
    /**
     * Fork: assets/wallpapers/ ships a curated batch, wallpapers.json names the entries. Every
     * asset missing from the catalog is imported once per batch stamp, and a fresh stamp also
     * turns hourly rotation on (never while the owner is on the daily wallpaper or has already
     * been rotating). Nothing here removes or overrides a wallpaper the owner chose.
     */
    private void importBundledBatch(Context context){
        try{
            String[] files=context.getAssets().list("wallpapers");
            if(files.length==0)return;
            SharedPreferences prefs=context.getSharedPreferences("bundled-wallpapers-batch",0);
            String stamp=new String(asset(context,"wallpapers.stamp"),Charsets.UTF_8).trim();
            if(stamp.equals(prefs.getString("stamp","")))return;
            JSONObject names=new JSONObject();
            try{
                JSONArray manifest=new JSONArray(new String(asset(context,"wallpapers/wallpapers.json"),Charsets.UTF_8));
                for(int i=0;i<manifest.length();i++){JSONObject item=manifest.getJSONObject(i);names.put(item.getString("file"),item.getString("name"));}
            }catch(IOException|JSONException missing){/* no manifest: fall back to asset file names */}
            int imported=0;
            for(String file:files){
                if(!file.toLowerCase(java.util.Locale.US).matches(".*\\.(jpg|jpeg|png|webp)$"))continue;
                String display=names.optString(file,file.replaceFirst("(?i)\\.(jpg|jpeg|png|webp)$","").replaceFirst("^[0-9]+-",""));
                boolean known=false;for(WallpaperCatalog.Entry entry:catalog.custom)if(display.equals(entry.name)){known=true;break;}
                if(known)continue;
                File stage=new File(directory,"incoming-batch.tmp");
                try(InputStream input=context.getAssets().open("wallpapers/"+file);OutputStream output=new FileOutputStream(stage)){
                    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))>0)output.write(buffer,0,n);
                }
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(stage.getPath(),options);
                if(options.outWidth<=0)continue;
                add(stage,display,options.outWidth,options.outHeight);imported++;
            }
            List<WallpaperCatalog.Entry> pictures=catalog.pictures();
            boolean daily=WallpaperCatalog.DAILY.equals(catalog.current);
            if(imported>0&&!catalog.rotating&&!daily&&pictures.size()>=2){
                String start=catalog.current;boolean in=false;
                for(WallpaperCatalog.Entry entry:pictures)if(entry.id.equals(start))in=true;
                rotation(false,60,in?start:null);
            }
            prefs.edit().putString("stamp",stamp).apply();
            if(imported>0)Log.i("QuietTV","Bundled wallpapers imported: "+imported);
        }catch(IOException e){Log.w("QuietTV","Bundled wallpapers unavailable: "+e);}
    }
    private static byte[] asset(Context context,String name) throws IOException {
        InputStream input=context.getAssets().open(name);ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try{byte[] chunk=new byte[8192];int n;while((n=input.read(chunk))>0)buffer.write(chunk,0,n);}finally{input.close();}
        return buffer.toByteArray();
    }
    File dailyDirectory() throws IOException {
        File folder=new File(directory,"daily");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Daily wallpaper storage unavailable");return folder;
    }
    File dailyFile(String source,int slot) {
        if(!WallpaperCatalog.dailySource(source)||(slot!=0&&slot!=1))throw new IllegalArgumentException("Invalid daily wallpaper slot");
        return new File(new File(directory,"daily"),source+"-"+slot+".jpg");
    }
    boolean cached(WallpaperCatalog.DailyImage image) { return image!=null&&dailyFile(image.source,image.slot).isFile()&&dailyFile(image.source,image.slot).length()>0; }
    synchronized String imageKey(String id) {
        if(!WallpaperCatalog.DAILY.equals(id)){
            WallpaperCatalog.Entry entry=catalog.find(id);
            if(entry!=null&&entry.video()&&entry.custom()){File poster=poster(id);return id+":"+poster.length()+":"+poster.lastModified();}
            return id;
        }
        WallpaperCatalog.DailyImage image=catalog.dailyImages.get(catalog.dailySource);
        return cached(image)?id+":"+image.source+":"+image.key+":"+image.slot:id+":missing";
    }
    synchronized void commitDaily(WallpaperCatalog.DailyImage image,File prepared,boolean apply) throws IOException {
        if(!WallpaperCatalog.BING.equals(image.source))throw new IOException("Unknown wallpaper source");
        WallpaperCatalog next=catalog.copy();
        if(prepared!=null){
            if(prepared.length()==0||prepared.length()>DailyWallpaperClient.MAX_IMAGE_BYTES)throw new IOException("Invalid daily wallpaper size");
            WallpaperCatalog.DailyImage old=catalog.dailyImages.get(image.source);image.slot=old==null?0:1-old.slot;
            if(!prepared.renameTo(dailyFile(image.source,image.slot)))throw new IOException("Unable to save daily wallpaper");
        }else if(!cached(image))throw new IOException("Daily wallpaper cache unavailable");
        next.updateDaily(image,apply);
        persist(next);
    }
    synchronized File file(String id) {
        WallpaperCatalog.Entry entry=catalog.find(id);return mediaFile(id,entry!=null&&entry.video());
    }
    File poster(String id){mediaFile(id,true);return new File(directory,id+"-poster.jpg");}
    private File mediaFile(String id,boolean video){
        if(!id.matches("user-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid wallpaper ID");
        // Keep historical private paths; image decoding detects the original JPG/PNG/WebP content.
        return new File(directory,id+(video?".mp4":".jpg"));
    }
    private void persist(WallpaperCatalog next) throws IOException {
        FileOutputStream stream=null;
        try { stream=catalogFile.startWrite(); stream.write(next.json().toString().getBytes(Charsets.UTF_8)); catalogFile.finishWrite(stream); catalog=next;DiagnosticLog.info("wallpaper.saved","current="+next.current+" rotating="+next.rotating); }
        catch(JSONException|IOException e) { if(stream!=null) catalogFile.failWrite(stream); DiagnosticLog.error("wallpaper.save.failed",e);throw new IOException("Unable to save wallpaper settings",e); }
    }
    synchronized void select(String id) throws IOException { WallpaperCatalog next=catalog.copy();next.select(id);persist(next); }
    synchronized void stopVideo() throws IOException {WallpaperCatalog next=catalog.copy();next.stopVideo();persist(next);}
    synchronized void rotation(boolean video,int minutes,String start) throws IOException { WallpaperCatalog next=catalog.copy();next.rotation(video,minutes,start);persist(next); }
    synchronized void next() throws IOException { WallpaperCatalog next=catalog.copy();next.next();persist(next); }
    synchronized void remove(String id) throws IOException {
        WallpaperCatalog.Entry entry=catalog.find(id); if(entry==null || WallpaperCatalog.DEFAULT.equals(id)||WallpaperCatalog.DAILY.equals(id)) return;
        WallpaperCatalog old=catalog.copy(),next=catalog.copy();next.remove(id);
        ScreensaverSettings oldSaver=screensavers.snapshot(old);
        // Move the reference before removing the media. Even an interrupted deletion stays valid.
        screensavers.reconcile(next);
        try{
            persist(next);
            if(entry.custom()){File deleted=mediaFile(id,entry.video());if(deleted.exists()&&!deleted.delete())throw new IOException("Unable to delete wallpaper file");}
        }catch(IOException failure){
            try{persist(old);screensavers.save(oldSaver);}catch(IOException rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
        if(!entry.custom())return;
        if(entry.video()&&poster(id).exists()&&!poster(id).delete())Log.w("QuietTV","Removed video poster retained");
    }
    synchronized void checkCapacity() throws IOException {
        if(directory.getUsableSpace()<30L*1024*1024) throw new IOException("电视存储空间不足 / Not enough TV storage");
    }
    synchronized WallpaperCatalog.Entry add(File ready,String filename,int width,int height) throws IOException {
        checkCapacity();
        String id="user-"+UUID.randomUUID();
        String name=filename.replaceAll("[\\p{Cntrl}/\\\\]"," ").replaceFirst("(?i)\\.(jpg|jpeg|png|webp)$","").trim();
        if(name.isEmpty()) name="我的壁纸 / My wallpaper";
        if(name.length()>48) name=name.substring(0,48);
        WallpaperCatalog.Entry entry=new WallpaperCatalog.Entry(id,name,width,height,ready.length());
        File destination=file(id);
        if(!ready.renameTo(destination)) throw new IOException("Unable to save wallpaper image");
        WallpaperCatalog next=catalog.copy();next.add(entry);
        try { persist(next); } catch(IOException e) { if(!destination.delete()) Log.w("QuietTV","Unindexed wallpaper retained after failed save"); throw e; }
        return entry;
    }
    long videoCapacity(){return Math.max(0,directory.getUsableSpace()-32L*1024*1024);}
    synchronized void checkVideoCapacity(long incoming) throws IOException {
        if(incoming>videoCapacity())throw VideoDiagnostics.failure("capacity","storage_space_low","电视存储空间不足 / Not enough TV storage");
    }
    boolean saveVideoPoster(String id,Bitmap image)throws IOException{
        File destination=poster(id);
        synchronized(this){WallpaperCatalog.Entry entry=catalog.find(id);if(entry==null||!entry.video()||destination.isFile())return false;}
        File pending=videoIncoming();
        try{
            try(FileOutputStream stream=new FileOutputStream(pending)){
                if(!image.compress(Bitmap.CompressFormat.JPEG,85,stream))throw new IOException("Poster encoding failed");stream.getFD().sync();
            }
            synchronized(this){
                // Deletion and final publication share this lock; encoding never holds it.
                WallpaperCatalog.Entry entry=catalog.find(id);if(entry==null||!entry.video()||destination.isFile())return false;
                if(!pending.renameTo(destination))throw new IOException("Unable to save video poster");return true;
            }
        }finally{if(pending.isFile()&&!pending.delete())Log.w("QuietTV","Video poster temporary file retained");}
    }
    synchronized WallpaperCatalog.Entry addVideo(File ready,String filename,WallpaperVideo.Info info,WallpaperUploadServer.Active active) throws IOException {
        long bytes=ready.length();
        if(!active.valid())throw VideoDiagnostics.failure("save","session_ended","上传会话已结束 / Session ended");
        String id="user-"+UUID.randomUUID(),name=filename.replaceAll("[\\p{Cntrl}/\\\\]"," ").replaceFirst("(?i)\\.(mp4|mov|mkv|webm|m4v|3gp|3g2|ts|m2ts|mts|mpg|mpeg|vob|flv)$", "").trim();
        if(name.isEmpty())name="动态壁纸 / Video wallpaper";if(name.length()>48)name=name.substring(0,48);
        WallpaperCatalog.Entry entry=new WallpaperCatalog.Entry(id,name,info.width,info.height,bytes,"video",info.mime,info.durationMs,info.fps,info.rotation,info.pixelRatio);
        File destination=mediaFile(id,true);if(!ready.renameTo(destination))throw VideoDiagnostics.failure("save","video_rename_failed","无法保存视频 / Unable to save video");
        WallpaperCatalog next=catalog.copy();next.add(entry);
        // A failed commit leaves this task's unindexed files, never removes an existing wallpaper.
        if(!active.valid())throw VideoDiagnostics.failure("save","session_ended","上传会话已结束 / Session ended");
        persist(next);return entry;
    }
    File videoIncoming(){return new File(directory,"upload-"+UUID.randomUUID()+".tmp");}
    synchronized void sound(boolean enabled)throws IOException{WallpaperCatalog next=catalog.copy();next.sound=enabled;persist(next);}
    File incoming() { return new File(directory,"incoming.tmp"); }
    File prepared() { return new File(directory,"prepared.tmp"); }
}
