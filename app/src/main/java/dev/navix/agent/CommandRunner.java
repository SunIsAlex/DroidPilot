package dev.navix.agent;

import org.json.JSONObject;
import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class CommandRunner {
    static JSONObject run(List<String> argv, long timeoutMs, int outputLimit) throws Exception {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean truncated=false, timeout=false;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try (InputStream stream=process.getInputStream()) {
            byte[] buffer=new byte[2048];
            while (true) {
                int available=stream.available();
                if(available>0) {
                    int count=stream.read(buffer,0,Math.min(buffer.length,available));
                    if(count>0) {
                        int keep=Math.min(count,outputLimit-bytes.size());
                        if(keep>0) bytes.write(buffer,0,keep);
                        if(keep<count) truncated=true;
                    }
                } else if(!process.isAlive()) break;
                else Thread.sleep(10);
                if(System.nanoTime()>=deadline) { timeout=true; break; }
            }
            if(timeout) { process.destroyForcibly(); process.waitFor(500,TimeUnit.MILLISECONDS); }
            String output=bytes.toString("UTF-8");
            int exit=process.isAlive() ? -1 : process.exitValue();
            boolean commandError=java.util.regex.Pattern.compile("(?im)^\\s*(Error(?: type \\d+)?[: ]|Exception|java\\.[\\w.]*Exception|Security exception|Status: (?!ok))").matcher(output).find();
            JSONObject result=new JSONObject().put("ok",!timeout && exit==0 && !commandError)
                .put("exitCode",exit).put("output",output).put("truncated",truncated).put("timedOut",timeout);
            if(timeout) result.put("error","am timed out; an already dispatched Android operation may still complete");
            else if(commandError || exit!=0) result.put("error","am command failed; inspect output");
            return result;
        } finally {
            if(process.isAlive()) process.destroyForcibly();
            process.getInputStream().close(); process.getOutputStream().close(); process.getErrorStream().close();
        }
    }
}
