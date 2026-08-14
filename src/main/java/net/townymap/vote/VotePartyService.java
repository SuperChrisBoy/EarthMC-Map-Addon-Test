package net.townymap.vote;

import net.townymap.TownyMapConfig;
import net.townymap.api.EarthMcApiClient;
import net.townymap.model.VotePartyStatus;
import java.util.concurrent.atomic.AtomicBoolean;

/** One global async cache shared by menus, HUD, and Xaero's World Map. */
public final class VotePartyService{
    private static final long REFRESH_MS=60_000L,RETRY_MS=15_000L;
    private final EarthMcApiClient api;private final TownyMapConfig config;private final AtomicBoolean loading=new AtomicBoolean();
    private volatile VotePartyStatus status;private volatile long lastAttempt;
    public VotePartyService(EarthMcApiClient api,TownyMapConfig config){this.api=api;this.config=config;}
    public void tick(){if(!config.votePartyEnabled)return;long now=System.currentTimeMillis(),delay=status==null?RETRY_MS:REFRESH_MS;if(now-lastAttempt<delay||!loading.compareAndSet(false,true))return;lastAttempt=now;api.fetchVoteParty().whenComplete((next,error)->{if(next!=null)status=next;loading.set(false);});}
    public VotePartyStatus status(){tick();return status;}
    public boolean loading(){return loading.get();}
}
