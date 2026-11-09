// Kas grupları ve hareket veritabanı
// min/max: haftalık önerilen set aralığı (bilimsel hacim önerilerine yakın)
const MUSCLES = {
  chest:      { name: "Göğüs",              min: 10, max: 20, group: "push" },
  front_delt: { name: "Ön Omuz",            min: 6,  max: 16, group: "push" },
  side_delt:  { name: "Yan Omuz",           min: 8,  max: 20, group: "push" },
  rear_delt:  { name: "Arka Omuz",          min: 6,  max: 18, group: "pull" },
  biceps:     { name: "Biceps",             min: 8,  max: 20, group: "pull" },
  triceps:    { name: "Triceps",            min: 8,  max: 18, group: "push" },
  forearm:    { name: "Ön Kol",             min: 4,  max: 14, group: "pull", minor: true },
  traps:      { name: "Trapez",             min: 4,  max: 16, group: "pull", minor: true },
  lats:       { name: "Kanat (Lat)",        min: 10, max: 20, group: "pull" },
  upper_back: { name: "Orta Sırt",          min: 8,  max: 20, group: "pull" },
  lower_back: { name: "Bel",                min: 4,  max: 12, group: "legs", minor: true },
  abs:        { name: "Karın",              min: 6,  max: 18, group: "core" },
  obliques:   { name: "Yan Karın",          min: 4,  max: 14, group: "core", minor: true },
  glutes:     { name: "Kalça",              min: 8,  max: 18, group: "legs" },
  quads:      { name: "Ön Bacak (Quad)",    min: 10, max: 20, group: "legs" },
  hamstrings: { name: "Arka Bacak",         min: 8,  max: 18, group: "legs" },
  adductors:  { name: "İç Bacak",           min: 4,  max: 14, group: "legs", minor: true },
  calves:     { name: "Baldır",             min: 8,  max: 18, group: "legs" },
};

// p: birincil kaslar (1 set = 1), s: ikincil kaslar (1 set = 0.5)
// bw: vücut ağırlığı hareketi (kg girmek opsiyonel, ek ağırlık olarak)
const EXERCISES = [
  // GÖĞÜS
  { id: "bench", name: "Bench Press (Halter)", cat: "Göğüs", p: ["chest"], s: ["front_delt", "triceps"] },
  { id: "db_bench", name: "Dumbbell Bench Press", cat: "Göğüs", p: ["chest"], s: ["front_delt", "triceps"] },
  { id: "incline_bench", name: "Incline Bench Press", cat: "Göğüs", p: ["chest", "front_delt"], s: ["triceps"] },
  { id: "incline_db", name: "Incline Dumbbell Press", cat: "Göğüs", p: ["chest", "front_delt"], s: ["triceps"] },
  { id: "decline_bench", name: "Decline Bench Press", cat: "Göğüs", p: ["chest"], s: ["triceps"] },
  { id: "chest_press", name: "Chest Press Makinesi", cat: "Göğüs", p: ["chest"], s: ["front_delt", "triceps"] },
  { id: "db_fly", name: "Dumbbell Fly", cat: "Göğüs", p: ["chest"], s: ["front_delt"] },
  { id: "cable_fly", name: "Cable Crossover", cat: "Göğüs", p: ["chest"], s: ["front_delt"] },
  { id: "pec_deck", name: "Pec Deck (Butterfly)", cat: "Göğüs", p: ["chest"], s: [] },
  { id: "pushup", name: "Şınav", cat: "Göğüs", p: ["chest"], s: ["front_delt", "triceps", "abs"], bw: true },
  { id: "dips_chest", name: "Dips (Göğüs)", cat: "Göğüs", p: ["chest", "triceps"], s: ["front_delt"], bw: true },

  // SIRT
  { id: "deadlift", name: "Deadlift", cat: "Sırt", p: ["lower_back", "glutes", "hamstrings"], s: ["traps", "upper_back", "forearm", "quads", "lats"] },
  { id: "pullup", name: "Barfiks (Pull-up)", cat: "Sırt", p: ["lats"], s: ["biceps", "upper_back", "rear_delt", "forearm"], bw: true },
  { id: "chinup", name: "Chin-up", cat: "Sırt", p: ["lats", "biceps"], s: ["upper_back"], bw: true },
  { id: "lat_pulldown", name: "Lat Pulldown", cat: "Sırt", p: ["lats"], s: ["biceps", "upper_back", "rear_delt"] },
  { id: "close_pulldown", name: "Dar Tutuş Pulldown", cat: "Sırt", p: ["lats"], s: ["biceps", "upper_back"] },
  { id: "barbell_row", name: "Barbell Row", cat: "Sırt", p: ["upper_back", "lats"], s: ["rear_delt", "biceps", "lower_back", "forearm"] },
  { id: "db_row", name: "Tek Kol Dumbbell Row", cat: "Sırt", p: ["lats", "upper_back"], s: ["rear_delt", "biceps"] },
  { id: "cable_row", name: "Seated Cable Row", cat: "Sırt", p: ["upper_back", "lats"], s: ["rear_delt", "biceps"] },
  { id: "tbar_row", name: "T-Bar Row", cat: "Sırt", p: ["upper_back", "lats"], s: ["rear_delt", "biceps", "lower_back"] },
  { id: "machine_row", name: "Row Makinesi", cat: "Sırt", p: ["upper_back", "lats"], s: ["rear_delt", "biceps"] },
  { id: "pullover", name: "Straight Arm Pulldown", cat: "Sırt", p: ["lats"], s: ["triceps"] },
  { id: "hyperext", name: "Hyperextension", cat: "Sırt", p: ["lower_back"], s: ["glutes", "hamstrings"], bw: true },
  { id: "shrug", name: "Shrug", cat: "Sırt", p: ["traps"], s: ["forearm"] },
  { id: "rack_pull", name: "Rack Pull", cat: "Sırt", p: ["traps", "lower_back", "upper_back"], s: ["glutes", "forearm"] },

  // OMUZ
  { id: "ohp", name: "Overhead Press (Halter)", cat: "Omuz", p: ["front_delt"], s: ["side_delt", "triceps", "traps"] },
  { id: "db_shoulder", name: "Dumbbell Shoulder Press", cat: "Omuz", p: ["front_delt"], s: ["side_delt", "triceps"] },
  { id: "arnold", name: "Arnold Press", cat: "Omuz", p: ["front_delt", "side_delt"], s: ["triceps"] },
  { id: "shoulder_machine", name: "Shoulder Press Makinesi", cat: "Omuz", p: ["front_delt"], s: ["side_delt", "triceps"] },
  { id: "lateral_raise", name: "Lateral Raise", cat: "Omuz", p: ["side_delt"], s: ["traps"] },
  { id: "cable_lateral", name: "Cable Lateral Raise", cat: "Omuz", p: ["side_delt"], s: [] },
  { id: "front_raise", name: "Front Raise", cat: "Omuz", p: ["front_delt"], s: [] },
  { id: "rear_fly", name: "Reverse Fly (Arka Omuz)", cat: "Omuz", p: ["rear_delt"], s: ["upper_back"] },
  { id: "reverse_pec", name: "Reverse Pec Deck", cat: "Omuz", p: ["rear_delt"], s: ["upper_back"] },
  { id: "face_pull", name: "Face Pull", cat: "Omuz", p: ["rear_delt"], s: ["upper_back", "traps"] },
  { id: "upright_row", name: "Upright Row", cat: "Omuz", p: ["side_delt", "traps"], s: ["biceps"] },

  // KOL
  { id: "bb_curl", name: "Barbell Curl", cat: "Kol", p: ["biceps"], s: ["forearm"] },
  { id: "db_curl", name: "Dumbbell Curl", cat: "Kol", p: ["biceps"], s: ["forearm"] },
  { id: "hammer", name: "Hammer Curl", cat: "Kol", p: ["biceps", "forearm"], s: [] },
  { id: "preacher", name: "Preacher Curl", cat: "Kol", p: ["biceps"], s: [] },
  { id: "incline_curl", name: "Incline Dumbbell Curl", cat: "Kol", p: ["biceps"], s: [] },
  { id: "cable_curl", name: "Cable Curl", cat: "Kol", p: ["biceps"], s: ["forearm"] },
  { id: "concentration", name: "Concentration Curl", cat: "Kol", p: ["biceps"], s: [] },
  { id: "pushdown", name: "Triceps Pushdown", cat: "Kol", p: ["triceps"], s: [] },
  { id: "rope_pushdown", name: "Rope Pushdown", cat: "Kol", p: ["triceps"], s: [] },
  { id: "skullcrusher", name: "Skull Crusher", cat: "Kol", p: ["triceps"], s: [] },
  { id: "oh_ext", name: "Overhead Triceps Extension", cat: "Kol", p: ["triceps"], s: [] },
  { id: "close_bench", name: "Close Grip Bench Press", cat: "Kol", p: ["triceps", "chest"], s: ["front_delt"] },
  { id: "dips_tri", name: "Bench Dips", cat: "Kol", p: ["triceps"], s: ["chest", "front_delt"], bw: true },
  { id: "kickback", name: "Triceps Kickback", cat: "Kol", p: ["triceps"], s: [] },
  { id: "wrist_curl", name: "Wrist Curl", cat: "Kol", p: ["forearm"], s: [] },
  { id: "farmer", name: "Farmer's Walk", cat: "Kol", p: ["forearm", "traps"], s: ["abs", "glutes"] },

  // BACAK
  { id: "squat", name: "Squat (Halter)", cat: "Bacak", p: ["quads", "glutes"], s: ["adductors", "lower_back", "hamstrings", "abs"] },
  { id: "front_squat", name: "Front Squat", cat: "Bacak", p: ["quads"], s: ["glutes", "abs", "upper_back"] },
  { id: "hack_squat", name: "Hack Squat", cat: "Bacak", p: ["quads"], s: ["glutes", "adductors"] },
  { id: "leg_press", name: "Leg Press", cat: "Bacak", p: ["quads", "glutes"], s: ["adductors", "hamstrings"] },
  { id: "goblet", name: "Goblet Squat", cat: "Bacak", p: ["quads", "glutes"], s: ["adductors", "abs"] },
  { id: "lunge", name: "Lunge", cat: "Bacak", p: ["quads", "glutes"], s: ["adductors", "hamstrings"] },
  { id: "bulgarian", name: "Bulgarian Split Squat", cat: "Bacak", p: ["quads", "glutes"], s: ["adductors", "hamstrings"] },
  { id: "leg_ext", name: "Leg Extension", cat: "Bacak", p: ["quads"], s: [] },
  { id: "rdl", name: "Romanian Deadlift", cat: "Bacak", p: ["hamstrings", "glutes"], s: ["lower_back", "forearm"] },
  { id: "leg_curl", name: "Lying Leg Curl", cat: "Bacak", p: ["hamstrings"], s: ["calves"] },
  { id: "seated_curl", name: "Seated Leg Curl", cat: "Bacak", p: ["hamstrings"], s: [] },
  { id: "hip_thrust", name: "Hip Thrust", cat: "Bacak", p: ["glutes"], s: ["hamstrings"] },
  { id: "glute_bridge", name: "Glute Bridge", cat: "Bacak", p: ["glutes"], s: ["hamstrings"], bw: true },
  { id: "abductor", name: "Abductor Makinesi", cat: "Bacak", p: ["glutes"], s: [] },
  { id: "adductor_m", name: "Adductor Makinesi", cat: "Bacak", p: ["adductors"], s: [] },
  { id: "good_morning", name: "Good Morning", cat: "Bacak", p: ["hamstrings", "lower_back"], s: ["glutes"] },
  { id: "calf_raise", name: "Ayakta Calf Raise", cat: "Bacak", p: ["calves"], s: [] },
  { id: "seated_calf", name: "Oturarak Calf Raise", cat: "Bacak", p: ["calves"], s: [] },
  { id: "step_up", name: "Step Up", cat: "Bacak", p: ["quads", "glutes"], s: ["hamstrings"] },

  // KARIN
  { id: "crunch", name: "Crunch", cat: "Karın", p: ["abs"], s: [], bw: true },
  { id: "cable_crunch", name: "Cable Crunch", cat: "Karın", p: ["abs"], s: ["obliques"] },
  { id: "leg_raise", name: "Hanging Leg Raise", cat: "Karın", p: ["abs"], s: ["obliques", "forearm"], bw: true },
  { id: "plank", name: "Plank (saniye = tekrar)", cat: "Karın", p: ["abs"], s: ["obliques", "front_delt"], bw: true },
  { id: "russian_twist", name: "Russian Twist", cat: "Karın", p: ["obliques"], s: ["abs"], bw: true },
  { id: "ab_wheel", name: "Ab Wheel Rollout", cat: "Karın", p: ["abs"], s: ["lats", "obliques"], bw: true },
  { id: "side_plank", name: "Side Plank", cat: "Karın", p: ["obliques"], s: ["abs"], bw: true },
  { id: "woodchop", name: "Cable Woodchopper", cat: "Karın", p: ["obliques"], s: ["abs"] },

  // TÜM VÜCUT
  { id: "clean", name: "Power Clean", cat: "Tüm Vücut", p: ["traps", "glutes", "quads"], s: ["hamstrings", "lower_back", "upper_back", "side_delt"] },
  { id: "kb_swing", name: "Kettlebell Swing", cat: "Tüm Vücut", p: ["glutes", "hamstrings"], s: ["lower_back", "abs", "front_delt"] },
  { id: "thruster", name: "Thruster", cat: "Tüm Vücut", p: ["quads", "front_delt"], s: ["glutes", "triceps", "abs"] },
  // EKİPMANSIZ (evde)
  { id: "bw_squat", name: "Vücut Ağırlığı Squat", cat: "Ekipmansız", p: ["quads", "glutes"], s: ["adductors"], bw: true },
  { id: "split_squat_bw", name: "Split Squat (ağırlıksız)", cat: "Ekipmansız", p: ["quads", "glutes"], s: ["adductors", "hamstrings"], bw: true },
  { id: "wall_sit", name: "Wall Sit (saniye = tekrar)", cat: "Ekipmansız", p: ["quads"], s: ["glutes"], bw: true },
  { id: "sl_rdl_bw", name: "Tek Bacak RDL (ağırlıksız)", cat: "Ekipmansız", p: ["hamstrings", "glutes"], s: ["lower_back"], bw: true },
  { id: "nordic", name: "Nordic Hamstring Curl", cat: "Ekipmansız", p: ["hamstrings"], s: ["glutes"], bw: true },
  { id: "pike_pushup", name: "Pike Push-up", cat: "Ekipmansız", p: ["front_delt"], s: ["triceps", "side_delt"], bw: true },
  { id: "diamond_pushup", name: "Diamond Şınav", cat: "Ekipmansız", p: ["triceps"], s: ["chest", "front_delt"], bw: true },
  { id: "door_row", name: "Inverted Row (masa / kapı)", cat: "Ekipmansız", p: ["upper_back", "lats"], s: ["biceps", "rear_delt"], bw: true },
  { id: "superman", name: "Superman", cat: "Ekipmansız", p: ["lower_back"], s: ["glutes", "upper_back"], bw: true },
  { id: "prone_yt", name: "Prone Y-T Raise", cat: "Ekipmansız", p: ["rear_delt"], s: ["upper_back", "traps"], bw: true },
  { id: "bw_calf", name: "Calf Raise (ağırlıksız)", cat: "Ekipmansız", p: ["calves"], s: [], bw: true },
  { id: "mountain_climber", name: "Mountain Climber", cat: "Ekipmansız", p: ["abs"], s: ["obliques", "front_delt"], bw: true },
  { id: "bag_curl", name: "Sırt Çantası Curl", cat: "Ekipmansız", p: ["biceps"], s: ["forearm"], bw: true },
  { id: "bag_lateral", name: "Su Şişesi / Çanta Lateral Raise", cat: "Ekipmansız", p: ["side_delt"], s: ["traps"], bw: true },
  { id: "bag_shrug", name: "Sırt Çantası Shrug", cat: "Ekipmansız", p: ["traps"], s: ["forearm"], bw: true },
  { id: "towel_hang", name: "Havlu / Çanta Tutuşu (saniye)", cat: "Ekipmansız", p: ["forearm"], s: ["traps"], bw: true },
  { id: "bicycle", name: "Bicycle Crunch", cat: "Ekipmansız", p: ["obliques"], s: ["abs"], bw: true },
  { id: "burpee", name: "Burpee", cat: "Tüm Vücut", p: ["quads", "chest"], s: ["front_delt", "triceps", "abs"], bw: true },
];

const PROTEIN_FOODS = [
  { name: "Yumurta (1 adet)", g: 6 },
  { name: "Whey (1 ölçek)", g: 24 },
  { name: "Tavuk göğsü (100g)", g: 31 },
  { name: "Kırmızı et (100g)", g: 26 },
  { name: "Ton balığı (1 kutu)", g: 25 },
  { name: "Lor peyniri (100g)", g: 12 },
  { name: "Yoğurt (200g)", g: 8 },
  { name: "Süt (250ml)", g: 8 },
  { name: "Mercimek (1 kase)", g: 18 },
  { name: "Beyaz peynir (50g)", g: 8 },
];

// Ekipman: bw = vücut ağırlığı, db = evde dumbbell/kettlebell, gym = tam salon
const DB_IDS = ["db_bench", "incline_db", "db_fly", "db_row", "db_shoulder", "arnold", "lateral_raise", "front_raise", "rear_fly",
  "db_curl", "hammer", "incline_curl", "concentration", "oh_ext", "kickback", "wrist_curl", "farmer", "goblet", "lunge",
  "bulgarian", "rdl", "kb_swing", "step_up", "shrug", "calf_raise", "thruster", "russian_twist", "glute_bridge", "hip_thrust"];
for (const e of EXERCISES) e.eq = e.bw ? "bw" : DB_IDS.includes(e.id) ? "db" : "gym";
// Evde barfiks barı olduğunu varsayma
for (const id of ["pullup", "chinup", "leg_raise", "dips_chest"]) EXERCISES.find((e) => e.id === id).eq = "gym";

// Sakatlık durumunda kaçınılacak hareketler
const INJURY_RISK = {
  shoulder: { name: "Omuz", avoid: ["pike_pushup", "ohp", "upright_row", "dips_chest", "dips_tri", "skullcrusher", "arnold", "front_raise", "decline_bench", "bench", "thruster"] },
  back:     { name: "Bel",  avoid: ["rdl", "sl_rdl_bw", "deadlift", "good_morning", "barbell_row", "rack_pull", "tbar_row", "squat", "clean", "kb_swing", "hyperext"] },
  knee:     { name: "Diz",  avoid: ["split_squat_bw", "wall_sit", "leg_ext", "lunge", "bulgarian", "hack_squat", "front_squat", "step_up", "burpee", "thruster"] },
  wrist:    { name: "Bilek", avoid: ["diamond_pushup", "pike_pushup", "mountain_climber", "pushup", "front_squat", "clean", "wrist_curl", "skullcrusher", "bb_curl"] },
};

const EQ_LEVEL = { bw: 0, db: 1, gym: 2 };
