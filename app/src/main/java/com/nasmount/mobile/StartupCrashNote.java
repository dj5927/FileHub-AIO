package com.nasmount.mobile;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded, app-local Java fatal trace, excluding messages and paths. */
final class StartupCrashNote {
    private static final String FILE="filehub-last-java-crash.txt";
    private static final int LIMIT=16384;
    private StartupCrashNote(){}
    static String format(Throwable failure,String build){
        StringBuilder out=new StringBuilder("FileHub AIO Java fatal error\n").append(build).append('\n');
        Throwable current=failure;
        for(int depth=0;current!=null&&depth<6;depth++){
            out.append(depth==0?"exception: ":"caused by: ").append(current.getClass().getName()).append('\n');
            StackTraceElement[] frames=current.getStackTrace();
            for(int i=0;i<Math.min(48,frames.length);i++){
                StackTraceElement frame=frames[i];
                out.append("  ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber()).append('\n');
                if(out.length()>=LIMIT)break;
            }
            if(out.length()>=LIMIT)break;
            Throwable next=current.getCause();
            if(next==current)break;
            current=next;
        }
        return out.substring(0,Math.min(out.length(),LIMIT));
    }
    static void install(Context context){
        final Context app=context;
        final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{
                File target=app.getFileStreamPath(FILE);
                File pending=app.getFileStreamPath(FILE+".tmp");
                String build=BuildConfig.VERSION_NAME+" r8Trial="+BuildConfig.R8_TRIAL+
                        " sdk="+android.os.Build.VERSION.SDK_INT;
                byte[] payload=format(error,build).getBytes(StandardCharsets.UTF_8);
                try(FileOutputStream stream=new FileOutputStream(pending)){
                    stream.write(payload,0,Math.min(payload.length,LIMIT));
                    stream.getFD().sync();
                }
                if(!pending.renameTo(target)){
                    try(FileOutputStream stream=new FileOutputStream(target)){
                        stream.write(payload,0,Math.min(payload.length,LIMIT));
                    }
                }
            }catch(Throwable ignored){}
            if(previous!=null)previous.uncaughtException(thread,error);
            else{android.os.Process.killProcess(android.os.Process.myPid());System.exit(10);}
        });
    }
    static String read(Context context){
        try(FileInputStream stream=new FileInputStream(context.getFileStreamPath(FILE))){
            byte[] data=new byte[LIMIT];int n=0,read;
            while(n<data.length&&(read=stream.read(data,n,data.length-n))>0)n+=read;
            return new String(data,0,n,StandardCharsets.UTF_8);
        }catch(Exception ignored){return "";}
    }
    // Android ANR traces may contain arbitrary application strings. Only
    // retain bounded Java main-thread stack frames, never the raw trace.
    static String mainFrames(java.io.InputStream stream)throws java.io.IOException{
        if(stream==null)return "";
        java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(stream,StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();
        boolean main=false;
        for(int i=0;i<2500;i++){
            String line=reader.readLine();if(line==null)break;
            String t=line.trim();
            if(t.startsWith("\"main\"")){main=true;continue;}
            if(!main)continue;
            if(t.startsWith("\""))break;
            if(t.matches("at [a-zA-Z0-9_$.$]+\\([a-zA-Z0-9_$ .:-]{1,120}\\)")){
                out.append("  ").append(t).append('\n');
                if(out.length()>5000)break;
            }
        }
        return out.toString();
    }
    /** System-maintained exit history also covers early/native failures. */
    static String report(Context context){
        StringBuilder report=new StringBuilder("FileHub AIO v")
                .append(BuildConfig.VERSION_NAME).append(" SDK ")
                .append(android.os.Build.VERSION.SDK_INT).append('\n');
        String saved=read(context);
        report.append(saved.isEmpty()?"No recorded Java fatal stack.\n":saved+"\n");
        if(android.os.Build.VERSION.SDK_INT>=30){
            try{
                android.app.ActivityManager manager=(android.app.ActivityManager)
                        context.getSystemService(Context.ACTIVITY_SERVICE);
                java.util.List<android.app.ApplicationExitInfo> exits=manager==null?
                        java.util.Collections.emptyList():manager.getHistoricalProcessExitReasons(
                                context.getPackageName(),0,12);
                int count=0;
                for(android.app.ApplicationExitInfo exit:exits){
                    int reason=exit.getReason();
                    if(reason!=android.app.ApplicationExitInfo.REASON_CRASH&&
                            reason!=android.app.ApplicationExitInfo.REASON_CRASH_NATIVE&&
                            reason!=android.app.ApplicationExitInfo.REASON_ANR&&
                            reason!=android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE&&
                            reason!=android.app.ApplicationExitInfo.REASON_SIGNALED)continue;
                    report.append("Android exit: ").append(new java.util.Date(exit.getTimestamp()))
                            .append(" reason=").append(reason).append(" status=").append(exit.getStatus()).append('\n');
                    if(count==0&&reason==android.app.ApplicationExitInfo.REASON_ANR){
                        try(java.io.InputStream trace=exit.getTraceInputStream()){
                            String frames=mainFrames(trace);
                            if(!frames.isEmpty())report.append("ANR main thread:\n").append(frames);
                        }catch(Exception ignored){}
                    }
                    if(++count>=5)break;
                }
                if(count==0)report.append("No crash/ANR/initialization exit in recent history.\n");
            }catch(Exception|LinkageError ignored){report.append("Android exit history unavailable.\n");}
        }else report.append("Android exit history requires API 30+.\n");
        String drive=OneDriveDiagnostics.report(context);
        if(!drive.isEmpty())report.append("\nOneDrive connection stages:\n").append(drive);
        return report.toString();
    }
}
