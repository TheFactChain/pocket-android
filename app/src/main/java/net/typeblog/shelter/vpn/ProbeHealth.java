package net.typeblog.shelter.vpn;

/** Native timestamps identify changed results; only elapsed time determines freshness. */
public final class ProbeHealth {
    private boolean ready,pending;
    private long observed,baseline,verifiedElapsed=-1;
    public boolean begin(){
        if(!ready || pending)return false;
        baseline=observed;pending=true;return true;
    }
    public boolean observe(long nativeTimestamp,long elapsed){
        ready=true;observed=nativeTimestamp;
        if(!pending || nativeTimestamp<=0 || nativeTimestamp==baseline)return false;
        pending=false;verifiedElapsed=elapsed;return true;
    }
    public boolean pending(){return pending;}
    public void expire(){pending=false;}
    public long verifiedElapsed(){return verifiedElapsed;}
    public boolean fresh(long elapsed){return verifiedElapsed>=0 && elapsed>=verifiedElapsed && elapsed-verifiedElapsed<=75000;}
}
