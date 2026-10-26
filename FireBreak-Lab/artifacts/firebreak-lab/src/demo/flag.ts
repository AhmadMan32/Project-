/** True in the offline demo build (VITE_DEMO=1): no server, synthetic terrain, in-browser simulation. */
export const IS_DEMO = import.meta.env.VITE_DEMO === '1';
/** Demo hosted on the open web (e.g. GitHub Pages): live Esri imagery and 3D DEM tiles, like the full app. */
export const LIVE_MAP = import.meta.env.VITE_LIVE_MAP === '1';
