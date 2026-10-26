export const C = {
  bg: '#020814',
  bg2: '#04122A',
  panel: 'rgba(8, 28, 58, 0.55)',
  panelStrong: 'rgba(10, 36, 72, 0.78)',
  border: 'rgba(56, 189, 248, 0.32)',
  borderBright: 'rgba(103, 232, 249, 0.75)',
  cyan: '#22D3EE',
  cyanSoft: '#67E8F9',
  blue: '#38BDF8',
  text: '#E6F6FF',
  sub: '#9CC3DD',
  muted: '#5F87A6',
  red: '#F87171',
  amber: '#FBBF24',
  green: '#34D399',
  purple: '#A78BFA',
};

export const levelColor = { kritik: C.red, uyari: C.amber, bilgi: C.blue } as const;

export const font = {
  title: { color: C.text, fontSize: 13, letterSpacing: 2.2, fontWeight: '700' as const },
  body: { color: C.text, fontSize: 15, lineHeight: 21 },
  sub: { color: C.sub, fontSize: 13, lineHeight: 18 },
  tiny: { color: C.muted, fontSize: 11, letterSpacing: 1.2 },
};
