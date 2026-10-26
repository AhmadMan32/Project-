// Tarih yardımcıları. Tüm "gün" hesapları cihazın yerel saatine göre yapılır.

const pad = (n: number) => String(n).padStart(2, '0');

export function dayKey(d: Date = new Date()): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function localStamp(d: Date): string {
  return `${dayKey(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// "2026-10-26" -> gün sonu (o gün içinde herhangi bir saat geçerli),
// "2026-10-26T14:00" -> o an.
export function dueDate(due: string): Date {
  if (due.length <= 10) {
    const [y, m, d] = due.split('-').map(Number);
    return new Date(y, m - 1, d, 23, 59, 59);
  }
  const [date, time] = due.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [hh, mm] = time.split(':').map(Number);
  return new Date(y, m - 1, d, hh, mm || 0);
}

export function dueDay(due: string): string {
  return due.slice(0, 10);
}

export function addDays(d: Date, n: number): Date {
  const x = new Date(d);
  x.setDate(x.getDate() + n);
  return x;
}

export function startOfDay(d: Date = new Date()): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

export function daysBetween(a: Date, b: Date): number {
  return Math.round((startOfDay(b).getTime() - startOfDay(a).getTime()) / 86400000);
}

const DAYS = ['Pazar', 'Pazartesi', 'Salı', 'Çarşamba', 'Perşembe', 'Cuma', 'Cumartesi'];
const MONTHS = ['Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık'];

export function trDate(d: Date): string {
  return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()} ${DAYS[d.getDay()]}`;
}

export function trTime(d: Date): string {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function dayName(d: Date): string {
  return DAYS[d.getDay()];
}

export function shortDay(d: Date): string {
  return `${d.getDate()} ${MONTHS[d.getMonth()].slice(0, 3)}`;
}

// Görev bitişini insan diliyle: "bugün 14:00", "yarın", "3 gün gecikti"
export function relativeDue(due: string, now: Date = new Date()): string {
  const d = dueDate(due);
  const diff = daysBetween(now, d);
  const time = due.length > 10 ? ` ${trTime(d)}` : '';
  if (d.getTime() < now.getTime()) {
    if (diff === 0) return `bugün${time} (geçti)`;
    return `${-diff} gün gecikti`;
  }
  if (diff === 0) return `bugün${time}`;
  if (diff === 1) return `yarın${time}`;
  if (diff < 7) return `${dayName(d)}${time}`;
  return shortDay(d);
}

export function greeting(now: Date = new Date()): string {
  const h = now.getHours();
  if (h < 5) return 'İyi geceler';
  if (h < 12) return 'Günaydın';
  if (h < 18) return 'İyi günler';
  return 'İyi akşamlar';
}

export function parseHHMM(s: string): { h: number; m: number } {
  const [h, m] = s.split(':').map(Number);
  return { h: Number.isFinite(h) ? h : 8, m: Number.isFinite(m) ? m : 30 };
}
