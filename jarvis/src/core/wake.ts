// Tanıma motoru "Jarvis"i farklı yazabiliyor: carvis, çarvis, jarvis, cervis...
// (JS'te \b Türkçe harfleri tanımadığı için sınırlar elle yazıldı.)
const WAKE = /(^|[\s,.!?])(?:(?:hey|hay|hei|ey)[\s,]+)?(?:j|c|ç|ş|dj)[ae]r?v[ıiy]s(?=$|[\s,.!?'’])[\s,.!?'’]*/i;

export function stripWake(text: string): { woke: boolean; rest: string } {
  const m = text.match(WAKE);
  if (!m) return { woke: false, rest: text };
  return { woke: true, rest: (text.slice(0, m.index! + m[1].length) + text.slice(m.index! + m[0].length)).trim() };
}
