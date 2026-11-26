package com.nasmount.mobile;

import android.app.job.*;
import android.content.*;

public final class TrashJobService extends JobService {
    private static final int JOB=734812;
    private Thread running;
    static void schedule(Context c){
        try{
            JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(scheduler==null||scheduler.getPendingJob(JOB)!=null)return;
            scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(c,TrashJobService.class))
                .setPeriodic(24*60*60*1000L).setPersisted(true).build());
        }catch(RuntimeException ignored){}
    }
    @Override public synchronized boolean onStartJob(JobParameters p){
        running=new Thread(()->{
            try{LocalTrash.cleanup(getApplicationContext());}
            finally{synchronized(this){if(running==Thread.currentThread()){running=null;jobFinished(p,false);}}}
        },"filehub-trash-expiration");running.start();return true;
    }
    @Override public synchronized boolean onStopJob(JobParameters p){
        if(running!=null){running.interrupt();running=null;}return true;
    }
}
