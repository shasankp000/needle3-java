/* Stand-in for libneedle so the Java bindings can be exercised without the real engine.
 * Build: gcc -shared -fPIC -o libneedle_mock.so mock/needle_mock.c */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static char g_tools[4096];
static int g_turn = 0;

int needle_load(const char* blob, uint64_t len) { return (blob && len > 0) ? 0 : -1; }

int needle_init(const char* system, const char* tools_json, const char* idx) {
    (void)idx;
    snprintf(g_tools, sizeof g_tools, "%s", tools_json ? tools_json : "");
    g_turn = 0;
    fprintf(stderr, "[mock] init system=%s tools=%s\n", system ? system : "(null)", g_tools);
    return (int)strlen(g_tools);
}

int needle_complete(const char* text, int max_new_tokens, char* out, int cap) {
    (void)max_new_tokens;
    const char* r;
    if (text[0] == '{' || text[0] == '[')
        r = "{\"type\":\"respond\",\"success\":true,\"error\":null,\"function_calls\":[],\"suppressed_calls\":[],\"reasoning\":\"\",\"confidence\":0.9}";
    else if (strstr(text, "weather"))
        r = "{\"type\":\"call\",\"success\":true,\"error\":null,\"function_calls\":[{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Lagos\"}}],\"suppressed_calls\":[],\"reasoning\":\"city\",\"confidence\":0.94,\"prefill_tps\":4300.0,\"decode_tps\":850.0,\"peak_ram_mb\":28.5}";
    else
        r = "{\"type\":\"call\",\"success\":true,\"error\":null,\"function_calls\":[],\"suppressed_calls\":[],\"reasoning\":\"\",\"confidence\":0.9}";
    int n = (int)strlen(r);
    if (n + 1 > cap) return -2;
    memcpy(out, r, n + 1);
    g_turn++;
    return n;
}

void needle_reset(void) { g_turn = 0; }

int needle_embed(const char* text, float* out, int cap) {
    (void)text;
    if (!out) return 3;
    for (int i = 0; i < 3 && i < cap; i++) out[i] = 0.5f * (i + 1);
    return 3;
}

const char* needle_last_error(void) { return "mock error"; }
