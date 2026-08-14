package net.townymap.model;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class VotePartyStatusTest{
 @Test void parsesOfficialServerSchema(){var s=VotePartyStatus.parse("{\"voteParty\":{\"target\":5000,\"numRemaining\":1250}}",10);assertEquals(5000,s.target());assertEquals(1250,s.remaining());assertEquals(3750,s.completed());assertEquals(75,s.percent());}
 @Test void rejectsMissingVoteParty(){assertNull(VotePartyStatus.parse("{}",10));}
}
