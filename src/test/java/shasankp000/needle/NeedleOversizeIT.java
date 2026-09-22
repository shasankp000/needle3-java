package shasankp000.needle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NeedleOversizeIT {
    @Test
    void oversizedToolSetReportsEngineDetail() {
        String filler = "Handles requests about " + "inventory ".repeat(60);
        List<Tool> tools = new ArrayList<>();
        for (int i = 0; i < 400; i++) {              // raise this if init unexpectedly succeeds
            tools.add(Tool.named("tool_" + i)
                    .description(filler + i)
                    .param("arg", Tool.string(filler))
                    .build());
        }
        NeedleException e = assertThrows(NeedleException.class,
                () -> Needle.builder().tools(tools).build());
        System.out.println("MESSAGE: " + e.getMessage());

        assertTrue(e.code() < 0);
        assertTrue(e.getMessage().matches("(?s).*\\(code -?\\d+\\): .+"),
                "no engine detail in: " + e.getMessage());
    }
}