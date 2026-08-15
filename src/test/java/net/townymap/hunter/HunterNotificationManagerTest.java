package net.townymap.hunter;

import net.townymap.TownyMapConfig;
import net.townymap.hunter.alert.HunterEvent;
import net.townymap.hunter.alert.HunterNotificationManager;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class HunterNotificationManagerTest {
    @Test void activityHistoryKeepsRoutineAndWarningEventsWhenChatIsDisabled(){
        TownyMapConfig config=new TownyMapConfig();
        config.hunterNotificationsInChat=false;
        config.hunterWarningsInChat=false;
        config.hunterActivityMaxEvents=200;
        ArrayList<String> chat=new ArrayList<>();
        HunterNotificationManager manager=new HunterNotificationManager(config,chat::add);
        manager.publish(HunterEvent.normal("online","Hunter online",1_000));
        manager.publish(HunterEvent.warning("nearby","Hunter nearby",2_000));
        assertTrue(chat.isEmpty());
        assertEquals(2,manager.history().size());
        assertEquals("Hunter nearby",manager.history().getFirst().title().getString());
        assertEquals("Hunter online",manager.history().get(1).title().getString());
    }
    @Test void activityOnlyNeverQueuesHudOrChat(){
        TownyMapConfig config=new TownyMapConfig();
        ArrayList<String> chat=new ArrayList<>();
        HunterNotificationManager manager=new HunterNotificationManager(config,chat::add);
        manager.activityOnly(HunterEvent.normal("auto-low","Automatic player is low risk",1_000));
        assertTrue(chat.isEmpty());
        assertTrue(manager.hudLines(System.currentTimeMillis()).isEmpty());
        assertEquals("Automatic player is low risk",manager.history().getFirst().title().getString());
    }
}
