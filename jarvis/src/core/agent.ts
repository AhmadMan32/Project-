// Jarvis ajan döngüsü: konuşma → niyet → gerçek veri → aksiyon → doğal cevap.
// Her istek; sabit kişilik + canlı durum + kullanıcının sözü ile yeni bir
// Claude konuşması olarak başlar, araç çağrıları bitene kadar sürer.

import Anthropic from '@anthropic-ai/sdk';
import { buildSnapshot, SYSTEM_PROMPT } from './prompt';
import { executeTool, TOOL_LABEL, TOOLS, ToolContext } from './tools';
import type { Alert, CalEvent, IntegrationStatus } from './types';

export const MODEL = 'claude-opus-5-5';
const MAX_STEPS = 10;

export type AgentEvent =
  | { type: 'tool'; name: string; label: string }
  | { type: 'tool_error'; name: string; message: string };

export interface AgentResult {
  text: string;
  status: IntegrationStatus; // Claude bağlantısının durumu
  actions: string[]; // çalışan araçların etiketleri
}

export interface AgentDeps extends ToolContext {
  client: Pick<Anthropic, 'beta'>;
  events: CalEvent[];
  alerts: Alert[];
  onEvent?: (e: AgentEvent) => void;
}

export function makeClient(apiKey: string): Anthropic {
  // Anahtar kullanıcının kendi cihazında güvenli depoda tutulur; arada sunucu yok.
  return new Anthropic({
    apiKey,
    baseURL: 'https://api.anthropic.com',
    dangerouslyAllowBrowser: true,
    timeout: 90_000,
    maxRetries: 2,
  });
}

export async function askJarvis(userText: string, deps: AgentDeps): Promise<AgentResult> {
  const { store, client } = deps;
  const now = store.now();
  const snapshot = buildSnapshot({ state: store.state, now, events: deps.events, alerts: deps.alerts });

  store.update((s) => {
    s.conversation.push({ at: now.toISOString(), role: 'user', text: userText });
  });

  const messages: Anthropic.Beta.BetaMessageParam[] = [
    { role: 'user', content: `${snapshot}\n\n<kullanici_mesaji>${userText}</kullanici_mesaji>` },
  ];
  const actions: string[] = [];
  let finalText = '';

  try {
    for (let step = 0; step < MAX_STEPS; step++) {
      const response = await client.beta.messages.create({
        model: MODEL,
        max_tokens: 16000,
        betas: ['server-side-fallback-2026-07-01'],
        fallbacks: 'default',
        output_config: { effort: 'medium' },
        system: [{ type: 'text', text: SYSTEM_PROMPT, cache_control: { type: 'ephemeral' } }],
        tools: TOOLS,
        messages,
      });

      if (response.stop_reason === 'refusal') {
        finalText = 'Bu isteğe yardımcı olamıyorum. Farklı şekilde sorarsan elimden geleni yaparım.';
        break;
      }

      const text = response.content
        .filter((b): b is Anthropic.Beta.BetaTextBlock => b.type === 'text')
        .map((b) => b.text)
        .join(' ')
        .trim();
      if (text) finalText = text;

      for (const b of response.content)
        if (b.type === 'server_tool_use' && b.name === 'web_search') {
          actions.push(TOOL_LABEL.web_search);
          deps.onEvent?.({ type: 'tool', name: 'web_search', label: TOOL_LABEL.web_search });
        }

      if (response.stop_reason === 'pause_turn') {
        messages.push({ role: 'assistant', content: response.content });
        continue;
      }
      if (response.stop_reason !== 'tool_use') break;

      messages.push({ role: 'assistant', content: response.content });
      const uses = response.content.filter((b): b is Anthropic.Beta.BetaToolUseBlock => b.type === 'tool_use');
      const results: Anthropic.Beta.BetaToolResultBlockParam[] = [];
      for (const u of uses) {
        const label = TOOL_LABEL[u.name] ?? u.name;
        actions.push(label);
        deps.onEvent?.({ type: 'tool', name: u.name, label });
        try {
          const out = await executeTool(u.name, (u.input ?? {}) as Record<string, unknown>, deps);
          results.push({ type: 'tool_result', tool_use_id: u.id, content: JSON.stringify(out ?? { ok: true }) });
        } catch (e) {
          const message = e instanceof Error ? e.message : String(e);
          deps.onEvent?.({ type: 'tool_error', name: u.name, message });
          results.push({ type: 'tool_result', tool_use_id: u.id, content: message, is_error: true });
        }
      }
      messages.push({ role: 'user', content: results });
    }
  } catch (e) {
    const { text, status } = describeError(e);
    store.log('system', 'ai_error', `Yapay zekâ hatası: ${text}`);
    return { text, status, actions };
  }

  finalText = toSpeech(finalText) || 'Tamamdır.';
  store.update((s) => {
    s.conversation.push({ at: store.now().toISOString(), role: 'jarvis', text: finalText });
  });
  return { text: finalText, status: 'ok', actions };
}

export function describeError(e: unknown): { text: string; status: IntegrationStatus } {
  if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError)
    return { text: 'Claude API anahtarım geçersiz görünüyor. Ayarlar bölümünden anahtarı kontrol eder misin?', status: 'hata' };
  if (e instanceof Anthropic.RateLimitError)
    return { text: 'Şu an çok fazla istek gönderdim, kısa bir süre sonra tekrar dener misin?', status: 'hata' };
  if (e instanceof Anthropic.APIConnectionError)
    return { text: 'Yapay zekâ servisine bağlanamıyorum. İnternet bağlantını kontrol eder misin?', status: 'hata' };
  if (e instanceof Anthropic.APIError) {
    if (e.status === 402 || /credit|billing/i.test(e.message))
      return { text: 'Claude hesabında kredi kalmamış görünüyor. Konsoldan bakiye eklemen gerekiyor.', status: 'hata' };
    return { text: `Yapay zekâ servisi bir hata döndürdü (kod ${e.status ?? '?'}). Birazdan tekrar deneyelim.`, status: 'hata' };
  }
  return { text: `Beklenmeyen bir sorun oluştu: ${e instanceof Error ? e.message : String(e)}`, status: 'hata' };
}

// Sesli okumaya uygun düz metin
export function toSpeech(t: string): string {
  return t
    .replace(/```[\s\S]*?```/g, '')
    .replace(/\*\*|__|`|#+\s/g, '')
    .replace(/^\s*[-*•]\s+/gm, '')
    .replace(/\[(.*?)\]\((.*?)\)/g, '$1')
    .replace(/\s+\n/g, '\n')
    .trim();
}

// Bağlantı testi: anahtar gerçekten çalışıyor mu?
export async function testClaude(client: Pick<Anthropic, 'beta'>): Promise<{ ok: boolean; message: string }> {
  try {
    const r = await client.beta.messages.create({
      model: MODEL,
      max_tokens: 200,
      output_config: { effort: 'low' },
      messages: [{ role: 'user', content: 'Sadece "Bağlantı tamam" yaz.' }],
    });
    const text = r.content.find((b): b is Anthropic.Beta.BetaTextBlock => b.type === 'text')?.text ?? '';
    return { ok: true, message: text.trim() || 'Bağlantı tamam' };
  } catch (e) {
    return { ok: false, message: describeError(e).text };
  }
}
