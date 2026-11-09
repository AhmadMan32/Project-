// Opsiyonel: Claude ile detaylı koçluk değerlendirmesi (Anthropic TypeScript SDK, tarayıcıdan)
async function askClaudeCoach(state, result) {
  const apiKey = state.apiKey;
  if (!apiKey) throw new Error("Önce Ayarlar sekmesinden Anthropic API anahtarını gir.");

  const { default: Anthropic } = await import("https://esm.sh/@anthropic-ai/sdk@1.4.0");
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true });

  const since = Date.now() - 28 * 86400000;
  const recent = state.workouts
    .filter((w) => dateToMs(w.date) >= since)
    .map((w) => ({
      tarih: w.date,
      hareket: (findExercise(w.exerciseId, state.customExercises) || {}).name || w.exerciseId,
      setler: w.sets.map((s) => `${s.kg || 0}kg x ${s.reps}`).join(", "),
    }));
  const weekly = Object.fromEntries(
    Object.keys(MUSCLES).map((m) => [MUSCLES[m].name, { set: result.week.vol[m], hedef: `${MUSCLES[m].min}-${MUSCLES[m].max}` }])
  );
  const targets = nutritionTargets(state.profile);

  const payload = {
    profil: { kilo: state.profile.weight, hedef: state.profile.goal },
    beslenme_hedefleri: targets,
    son_7_gun_kas_hacmi: weekly,
    son_4_hafta_antrenmanlar: recent,
    yerel_analiz_bulgulari: result.insights.map((i) => `${i.title}: ${i.text}`),
  };

  const response = await client.beta.messages.create({
    model: "claude-opus-5-5",
    max_tokens: 16000,
    thinking: { type: "adaptive" },
    output_config: { effort: "medium" },
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    system:
      "Sen deneyimli bir fitness koçusun. Kullanıcının antrenman verilerini inceleyip Türkçe, kısa ve uygulanabilir öneriler verirsin. " +
      "Şu başlıkları kullan: 1) Genel durum, 2) Fazla / az çalışan kaslar, 3) Gelişim yorumu, 4) Gelecek hafta için örnek program (gün gün hareket, set x tekrar), 5) Beslenme (protein, kreatin). " +
      "Tıbbi teşhis koyma; ağrı veya sakatlık şüphesinde uzmana yönlendir.",
    messages: [{ role: "user", content: "Verilerim:\n" + JSON.stringify(payload, null, 1) }],
  });

  if (response.stop_reason === "refusal") {
    throw new Error("Claude bu isteği yanıtlayamadı. Lütfen daha sonra tekrar dene.");
  }
  return response.content.filter((b) => b.type === "text").map((b) => b.text).join("\n").trim();
}
