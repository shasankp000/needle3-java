package shasankp000.needle;

import org.junit.jupiter.api.Test;

import java.util.Map;

class NeedleChainIT {
    @Test
    void chainProbe() {
        Tool mine = Tool.named("mine_block")
                .description("Gather, chop, collect or mine blocks from the world, like wood, logs, stone or ore")
                .param("block", Tool.enumOf("block to collect",
                        "oak_log", "birch_log", "cobblestone", "dirt", "iron_ore"))
                .param("count", Tool.integer(null, 1, 64))
                .build();
        Tool craft = Tool.named("craft_item")
                .description("Craft an item at a crafting table from materials already in the inventory; never used to gather materials")
                .param("item", Tool.enumOf("item to craft",
                        "oak_planks", "crafting_table", "stick", "wooden_pickaxe"))
                .param("count", Tool.integer(null, 1, 64))
                .build();
        Tool follow = Tool.named("follow_player")
                .description("Follow a player around")
                .param("player", Tool.string("player name"))
                .build();
        Tool stop = Tool.named("stop")
                .description("Stop whatever you are doing")
                .build();

        Tool other = Tool.named("unsupported_request")
                .description("Use when the request asks for something none of the other tools can do, "
                        + "such as building structures, trading, or chatting")
                .param("request", Tool.string("the request in the player's words"))
                .build();

        try (Needle n = Needle.builder().tools(mine, craft, follow, stop, other).autoDate(false).build()) {
            String[] queries = {
                    "mine 10 oak logs",
                    "mine 10 oak logs and craft 4 oak planks",
                    "follow Steve, then stop",
                    "get me some wood and make a crafting table",
                    "mine 5 diamonds",
                    "build me a shelter out of 20 oak planks",
                    "craft 3 beds"
            };
            for (String q : queries) {
                n.reset();
                NeedleResponse r = n.complete(q);
                System.out.println("CHAIN " + q + " | calls=" + r.raw().path("function_calls")
                        + " | suppressed=" + r.raw().path("suppressed_calls")
                        + " | ungrounded=" + r.ungrounded() + " | conf=" + r.confidence()
                        + " | reason=" + r.raw().path("reason") + " | why=" + r.raw().path("reasoning")
                );
            }

            n.reset();
            Map<String, Needle.ToolHandler> h = Map.of(
                    "mine_block", a -> Map.of("ok", true, "mined", a.path("count").asInt()),
                    "craft_item", a -> Map.of("ok", true, "crafted", a.path("count").asInt()),
                    "follow_player", a -> Map.of("ok", true),
                    "stop", a -> Map.of("ok", true));

            n.reset();
            var out = n.run("mine 10 oak logs and craft 4 oak planks", h, 6);
            System.out.println("RUN results=" + out.results() + " | final=" + out.last().raw());

            for (String q : new String[]{"build me a shelter", "mine 10 oak logs and craft 4 oak planks"}) {
                n.reset();
                var g = n.run(q, h, 6, true, 0.7);
                System.out.println("GATE " + q + " | verdict=" + g.verdict() + " | results=" + g.results());
            }

        }
    }
}