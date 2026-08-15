package net.townymap.teleport;

import net.townymap.model.PlayerFullData;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TeleportAccessCacheIdentityTest {
    @Test void unchangedAccessIdentityKeepsPersistentSnapshot(){
        assertTrue(TeleportAccessService.sameAccessIdentity(player("Town","Nation",false,false,List.of("resident"),List.of()),player("town","nation",false,false,List.of("RESIDENT"),List.of())));
    }
    @Test void townNationLeadershipAndRanksInvalidateSnapshot(){
        PlayerFullData base=player("Town","Nation",false,false,List.of("resident"),List.of());
        assertFalse(TeleportAccessService.sameAccessIdentity(base,player("Other","Nation",false,false,List.of("resident"),List.of())));
        assertFalse(TeleportAccessService.sameAccessIdentity(base,player("Town","Other",false,false,List.of("resident"),List.of())));
        assertFalse(TeleportAccessService.sameAccessIdentity(base,player("Town","Nation",true,false,List.of("resident"),List.of())));
        assertFalse(TeleportAccessService.sameAccessIdentity(base,player("Town","Nation",false,false,List.of("resident","trusted"),List.of())));
    }
    private static PlayerFullData player(String town,String nation,boolean mayor,boolean king,List<String> townRanks,List<String> nationRanks){return new PlayerFullData("Player","","","Player","","",town,nation,1,2,3,true,false,mayor,king,!town.isBlank(),!nation.isBlank(),100,0,List.of(),townRanks,nationRanks);}
}
