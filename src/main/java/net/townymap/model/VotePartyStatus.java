package net.townymap.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Immutable vote-party progress from the official EarthMC server endpoint. */
public record VotePartyStatus(int target,int remaining,long fetchedAtMs){
    public int completed(){return Math.max(0,target-remaining);}
    public int percent(){return target<=0?0:Math.clamp((int)Math.round(completed()*100.0/target),0,100);}
    public static VotePartyStatus parse(String json,long now){JsonObject root=JsonParser.parseString(json).getAsJsonObject();JsonObject vp=root.getAsJsonObject("voteParty");if(vp==null)return null;int target=vp.get("target").getAsInt(),remaining=vp.get("numRemaining").getAsInt();return target>0&&remaining>=0?new VotePartyStatus(target,remaining,now):null;}
}
