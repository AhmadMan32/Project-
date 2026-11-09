// İnsan vücudu SVG modeli (ön + arka). Sol yarı çizilir, sağ yarı aynalanır.
const BODY_SILHOUETTE =
  "M100 50 L91 51 L88 56 L70 62 Q50 66 48 86 L45 112 Q42 146 45 186 L43 204 L53 206 L58 188 Q64 162 66 140 L70 122 Q69 156 73 188 L71 198 Q63 244 70 292 Q65 334 72 382 L68 398 L89 398 L88 382 Q95 334 92 296 Q98 252 98 212 L100 212 Z";

const FRONT_MUSCLES = {
  traps:      ["M90 54 L72 62 Q82 64 92 63 Z"],
  front_delt: ["M72 63 Q57 66 54 80 Q58 90 62 94 Q66 82 76 72 Z"],
  side_delt:  ["M54 80 Q48 90 50 104 L56 102 Q57 92 61 93 Z"],
  chest:      ["M98 66 L78 68 Q67 80 68 98 Q82 108 98 104 Z"],
  biceps:     ["M57 104 Q50 118 52 136 L62 136 Q67 118 66 100 Z"],
  forearm:    ["M52 140 Q46 160 47 184 L55 184 Q62 162 63 140 Z"],
  abs:        ["M87 108 L98 108 L98 178 L89 174 Q84 142 87 108 Z"],
  obliques:   ["M85 108 Q73 112 73 130 Q74 156 87 172 Q82 140 85 108 Z"],
  quads:      ["M78 198 Q68 234 73 282 L88 284 Q96 242 95 202 Z"],
  adductors:  ["M96 204 Q95 232 91 262 L98 250 L98 204 Z"],
  calves:     ["M74 300 Q69 332 76 372 L84 372 Q89 332 87 300 Z"],
};

const BACK_MUSCLES = {
  traps:      ["M98 50 L88 56 L72 64 Q88 70 98 98 Z"],
  rear_delt:  ["M71 65 Q56 68 53 84 Q57 92 62 94 Q66 80 77 72 Z"],
  side_delt:  ["M53 84 Q48 92 50 104 L56 102 Q57 94 61 94 Z"],
  upper_back: ["M80 74 Q71 84 74 100 L96 104 L96 94 Z"],
  lats:       ["M73 103 Q66 122 74 150 Q86 158 96 162 L96 107 Z"],
  triceps:    ["M57 100 Q50 118 52 136 L62 136 Q67 118 66 98 Z"],
  forearm:    ["M52 140 Q46 160 47 184 L55 184 Q62 162 63 140 Z"],
  lower_back: ["M86 162 L98 165 L98 192 L84 190 Z"],
  glutes:     ["M98 194 L80 196 Q70 212 76 232 Q90 240 98 234 Z"],
  hamstrings: ["M76 238 Q70 262 74 292 L88 292 Q96 264 96 238 Z"],
  calves:     ["M74 302 Q65 328 74 362 L86 362 Q92 328 87 302 Z"],
};

function buildBodySVG(view, colorFor) {
  const muscles = view === "front" ? FRONT_MUSCLES : BACK_MUSCLES;
  const half = (inner) =>
    `<g>${inner}</g><g transform="translate(200,0) scale(-1,1)">${inner}</g>`;

  let base = `<path d="${BODY_SILHOUETTE}" class="silhouette"/>`;
  let musc = "";
  for (const [id, paths] of Object.entries(muscles)) {
    const c = colorFor(id);
    for (const d of paths) {
      musc += `<path d="${d}" class="muscle" data-muscle="${id}" fill="${c}"/>`;
    }
  }
  return `
  <svg viewBox="0 0 200 410" class="body-svg" role="img" aria-label="${view === "front" ? "Ön" : "Arka"} görünüm">
    <circle cx="100" cy="30" r="19" class="silhouette"/>
    ${half(base)}
    ${half(musc)}
    <text x="100" y="406" text-anchor="middle" class="body-label">${view === "front" ? "ÖN" : "ARKA"}</text>
  </svg>`;
}
