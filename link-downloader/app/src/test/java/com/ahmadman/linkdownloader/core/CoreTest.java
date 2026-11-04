package com.ahmadman.linkdownloader.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class CoreTest {

    // ------------------------------------------------------------------ links

    @Test
    public void findsLinksInShareTexts() {
        // what the apps put into the share sheet
        assertEquals("https://www.instagram.com/reel/C9xYz12AbCd/?igsh=MWtiZ2Q5",
                LinkDetector.extractUrl("https://www.instagram.com/reel/C9xYz12AbCd/?igsh=MWtiZ2Q5"));
        assertEquals("https://vm.tiktok.com/ZMhAbCdEf/",
                LinkDetector.extractUrl("Bu videoya göz at! #tiktok https://vm.tiktok.com/ZMhAbCdEf/ (TikTok'ta)"));
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc",
                LinkDetector.extractUrl("Watch this: https://youtu.be/dQw4w9WgXcQ?si=abc."));
        assertEquals("https://youtube.com/shorts/aBcDeF12345",
                LinkDetector.extractUrl("youtube.com/shorts/aBcDeF12345"));
        assertEquals("https://x.com/user/status/1234567890",
                LinkDetector.extractUrl("“https://x.com/user/status/1234567890”"));
        assertNull(LinkDetector.extractUrl("link yok burada"));
        assertNull(LinkDetector.extractUrl(null));
    }

    @Test
    public void detectsPlatforms() {
        assertEquals(Platform.YOUTUBE, LinkDetector.detect("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(Platform.YOUTUBE, LinkDetector.detect("https://m.youtube.com/shorts/abc"));
        assertEquals(Platform.YOUTUBE, LinkDetector.detect("https://youtu.be/abc"));
        assertEquals(Platform.YOUTUBE, LinkDetector.detect("https://music.youtube.com/watch?v=abc"));
        assertEquals(Platform.INSTAGRAM, LinkDetector.detect("https://www.instagram.com/reel/abc/"));
        assertEquals(Platform.INSTAGRAM, LinkDetector.detect("https://instagram.com/p/abc"));
        assertEquals(Platform.TIKTOK, LinkDetector.detect("https://www.tiktok.com/@user/video/123"));
        assertEquals(Platform.TIKTOK, LinkDetector.detect("https://vt.tiktok.com/ZS123/"));
        assertEquals(Platform.TWITTER, LinkDetector.detect("https://x.com/a/status/1"));
        assertEquals(Platform.TWITTER, LinkDetector.detect("https://twitter.com/a/status/1"));
        assertEquals(Platform.FACEBOOK, LinkDetector.detect("https://fb.watch/abc/"));
        assertEquals(Platform.PINTEREST, LinkDetector.detect("https://tr.pinterest.com/pin/123/"));
        assertEquals(Platform.SOUNDCLOUD, LinkDetector.detect("https://soundcloud.com/a/b"));
        assertEquals(Platform.OTHER, LinkDetector.detect("https://example.com/video.mp4"));
        // look-alike domains are not the real site
        assertEquals(Platform.OTHER, LinkDetector.detect("https://notyoutube.com/watch?v=1"));
        assertEquals(Platform.OTHER, LinkDetector.detect("https://instagram.com.evil.example/reel/x"));
    }

    @Test
    public void removesTrackingParameters() {
        assertEquals("https://www.instagram.com/reel/C9xYz12AbCd/",
                LinkDetector.clean("https://www.instagram.com/reel/C9xYz12AbCd/?igsh=MWtiZ2Q5&utm_source=ig_web_copy_link"));
        assertEquals("https://www.instagram.com/p/abc/?img_index=2",
                LinkDetector.clean("https://www.instagram.com/p/abc/?img_index=2&igsh=x"));
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42",
                LinkDetector.clean("https://youtu.be/dQw4w9WgXcQ?si=abc&t=42"));
        assertEquals("https://www.youtube.com/watch?v=abc&list=PL1",
                LinkDetector.clean("https://www.youtube.com/watch?v=abc&list=PL1&pp=ygU&feature=share"));
        assertEquals("https://www.tiktok.com/@a/video/1",
                LinkDetector.clean("https://www.tiktok.com/@a/video/1?_r=1&_t=8abc&is_from_webapp=1"));
        assertEquals("https://x.com/a/status/1", LinkDetector.clean("https://x.com/a/status/1?s=20&t=abc"));
        assertEquals("https://example.com/v?id=5", LinkDetector.clean("https://example.com/v?id=5&utm_medium=x"));
        assertEquals("https://example.com/v", LinkDetector.clean("https://example.com/v"));
    }

    // ------------------------------------------------------------------ commands

    private static int indexOf(List<String> a, String opt) {
        return a.indexOf(opt);
    }

    @Test
    public void videoCommandAsks1080pMp4() {
        List<String> a = Commands.video(1080, true, "/out/%(title)s.%(ext)s", null);
        assertEquals("res:1080,fps,vcodec:h264,acodec:aac", a.get(indexOf(a, "-S") + 1));
        assertEquals("bv*+ba/b", a.get(indexOf(a, "-f") + 1));
        assertEquals("mp4", a.get(indexOf(a, "--merge-output-format") + 1));
        assertEquals("mp4", a.get(indexOf(a, "--remux-video") + 1));
        assertTrue(a.contains("--no-playlist"));
        assertTrue(indexOf(a, "--no-quiet") > indexOf(a, "--print")); // keep step lines visible
        assertFalse(a.contains("--cookies"));
        assertEquals("res,fps", Commands.video(0, false, "x", null).get(indexOf(Commands.video(0, false, "x", null), "-S") + 1));
        List<String> c = Commands.video(720, true, "x", "/data/ig.txt");
        assertEquals("/data/ig.txt", c.get(indexOf(c, "--cookies") + 1));
    }

    @Test
    public void audioCommandAsksBestMp3() {
        List<String> a = Commands.audio(Commands.MP3, "/out/%(title)s.%(ext)s", null);
        assertTrue(a.contains("-x"));
        assertEquals("mp3", a.get(indexOf(a, "--audio-format") + 1));
        assertEquals("320K", a.get(indexOf(a, "--audio-quality") + 1));
        assertTrue(a.contains("--embed-thumbnail"));
        List<String> m = Commands.audio(Commands.M4A, "x", null);
        assertEquals("ba[ext=m4a]/ba/b", m.get(indexOf(m, "-f") + 1));
        assertFalse(m.contains("--audio-quality"));
    }

    @Test
    public void finalPathIsReadFromOutput() {
        String out = "[download] 100% of 5.00MiB\n[Merger] Merging formats into \"/x/a.mp4\"\n"
                + Commands.PATH_MARKER + "/x/Şarkı [abc].mp4\n";
        assertEquals("/x/Şarkı [abc].mp4", Commands.findPath(out));
        assertNull(Commands.findPath("nothing"));
        assertNull(Commands.findPath(Commands.PATH_MARKER + "NA"));
    }

    // ------------------------------------------------------------------ errors & progress

    @Test
    public void classifiesErrors() {
        assertEquals(ErrorHints.Reason.BOT_CHECK, ErrorHints.classify(
                "ERROR: [youtube] aqz: Sign in to confirm you’re not a bot. Use --cookies-from-browser"));
        assertEquals(ErrorHints.Reason.LOGIN_REQUIRED, ErrorHints.classify(
                "ERROR: [Instagram] C9x: Requested content is not available, rate-limit reached or login required. Use --cookies"));
        assertEquals(ErrorHints.Reason.PRIVATE, ErrorHints.classify("ERROR: [youtube] x: Private video. Sign in if you've been granted access"));
        assertEquals(ErrorHints.Reason.UNAVAILABLE, ErrorHints.classify("ERROR: [youtube] x: Video unavailable"));
        assertEquals(ErrorHints.Reason.UNSUPPORTED, ErrorHints.classify("ERROR: Unsupported URL: https://example.com"));
        assertEquals(ErrorHints.Reason.RATE_LIMITED, ErrorHints.classify("ERROR: HTTP Error 429: Too Many Requests"));
        assertEquals(ErrorHints.Reason.NETWORK, ErrorHints.classify("ERROR: Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>"));
        assertEquals(ErrorHints.Reason.GEO_BLOCKED, ErrorHints.classify("ERROR: The uploader has not made this video available in your country"));
        assertEquals(ErrorHints.Reason.UNKNOWN, ErrorHints.classify("something odd"));
        assertEquals("Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.",
                ErrorHints.lastError("WARNING: x\nERROR: [youtube] aqz-KE-bpKQ: Sign in to confirm you’re not a bot. "
                        + "Use --cookies-from-browser or --cookies for the authentication. See  https://github.com/x for how"));
    }

    @Test
    public void parsesProgressLines() {
        Progress p = Progress.parse("[download]  45.3% of ~  12.34MiB at    2.10MiB/s ETA 00:05 (frag 3/10)");
        assertEquals(Progress.Step.DOWNLOADING, p.step);
        assertEquals(45.3f, p.percent, 0.01);
        assertEquals("12.34MiB", p.total);
        assertEquals("2.10MiB/s", p.speed);
        assertEquals("00:05", p.eta);
        Progress done = Progress.parse("[download] 100% of   10.00MiB in 00:00:03 at 3.21MiB/s");
        assertEquals(100f, done.percent, 0.01);
        assertEquals(Progress.Step.MERGING, Progress.parse("[Merger] Merging formats into \"a.mp4\"").step);
        assertEquals(Progress.Step.CONVERTING, Progress.parse("[ExtractAudio] Destination: a.mp3").step);
        assertEquals(Progress.Step.FINISHING, Progress.parse("[EmbedThumbnail] ffmpeg: Adding thumbnail").step);
        assertNull(Progress.parse("[youtube] Extracting URL"));
        assertEquals("4:07", Progress.duration(247));
        assertEquals("1:05:09", Progress.duration(3909));
    }

    @Test
    public void writesNetscapeCookies() {
        String header = "csrftoken=abc; sessionid=123%3Axyz; ds_user_id=99; broken";
        String txt = Cookies.netscape("instagram.com", header, 2000000000L);
        assertTrue(txt.startsWith("# Netscape HTTP Cookie File\n"));
        assertTrue(txt.contains(".instagram.com\tTRUE\t/\tTRUE\t2000000000\tsessionid\t123%3Axyz\n"));
        assertEquals(4, txt.split("\n").length); // header + 3 valid cookies
        assertTrue(Cookies.hasInstagramSession(header));
        assertFalse(Cookies.hasInstagramSession("csrftoken=abc; sessionid="));
    }

    /** Writes the exact argument lists for the desktop yt-dlp check (see README). */
    @Test
    public void exportsArgumentsForPipelineCheck() throws Exception {
        File dir = new File(System.getProperty("user.dir"), "build/test-out");
        dir.mkdirs();
        export(new File(dir, "args-video.txt"), Commands.video(1080, true, Commands.template("OUT"), null));
        export(new File(dir, "args-audio.txt"), Commands.audio(Commands.MP3, Commands.template("OUT"), null));
        export(new File(dir, "args-m4a.txt"), Commands.audio(Commands.M4A, Commands.template("OUT"), null));
    }

    private static void export(File f, List<String> args) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (String a : args) {
                w.write(a);
                w.write('\n');
            }
        }
    }
}
