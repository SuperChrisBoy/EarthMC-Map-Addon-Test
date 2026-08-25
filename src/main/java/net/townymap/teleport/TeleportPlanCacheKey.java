package net.townymap.teleport;

/** Exact inputs that can change route planning; camera movement/render frames are intentionally absent. */
public record TeleportPlanCacheKey(long targetXBits,long targetZBits,long dataRevision,String primaryTown){
    public static TeleportPlanCacheKey of(double x,double z,long revision,String primaryTown){return new TeleportPlanCacheKey(Double.doubleToLongBits(x),Double.doubleToLongBits(z),revision,primaryTown==null?"":primaryTown);}
}
