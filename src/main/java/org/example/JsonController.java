package org.example;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.GainProcessor;
import be.tarsos.dsp.PitchShifter;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.io.jvm.WaveformWriter;
import be.tarsos.dsp.resample.RateTransposer;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.*;

@RestController
public class JsonController {

    private static final int BUFFER_SIZE = 4096;

    private static final int BUFFER_SIZE_SMALL = 512;
    private static final int BUFFER_SIZE_SMALL_DIFF = 32;
    private static final double GAIN = 3;
    private static final int SAMPLE_RATE = 22050;

    @SuppressWarnings("unchecked")
    @Consumes(MediaType.APPLICATION_JSON)
    @PostMapping("/api/tts")
    @Produces({"audio/wav", MediaType.TEXT_PLAIN})
    public ResponseEntity<?> create(@RequestBody RequestBodyModel requestBody, @RequestHeader HttpHeaders headers, HttpServletRequest req) {
        try {
            String outputFilePath = UUID.randomUUID() + ".wav";
            int exitCode = processConversion(requestBody.getText(), outputFilePath);

            if (exitCode != 0) {
                return new ResponseEntity<>("Error during TTS conversion", HttpStatus.INTERNAL_SERVER_ERROR);
            }

//            processAudioFile(outputFilePath, Double.parseDouble("0.95"));
//            applyEffects(outputFilePath);

            return buildResponse(outputFilePath, headers);

        } catch (Exception e) {
            return new ResponseEntity<>("Exception occurred: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ResponseEntity<?> buildResponse(String outputFilePath, HttpHeaders headers) throws IOException {
        String acceptHeader = headers.getFirst(HttpHeaders.ACCEPT);

        // If the client requests text/plain response
        if (org.springframework.http.MediaType.TEXT_PLAIN_VALUE.equals(acceptHeader)) {
            return new ResponseEntity<>(new File(outputFilePath).getName(), HttpStatus.OK);
        }

        File file = new File(outputFilePath);
        if (!file.exists()) {
            return new ResponseEntity<>("Audio file not found", HttpStatus.NOT_FOUND);
        }

        // Serve the audio file as a byte stream
        InputStreamResource resource = new InputStreamResource(new FileInputStream(file));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("audio/wav"))
                .contentLength(file.length())
                .body(resource);
    }

    @SuppressWarnings("squid:S4721")
    private int processConversion(String text, String outputFilePath) throws IOException, InterruptedException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text cannot be null or empty.");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                "etc/resource/piper/piper",
                "--model", "etc/resource/piper/cs_CZ-jirka-medium.onnx",
                "--output_file", outputFilePath
        );

        Process process = processBuilder.start();
        try (OutputStream os = process.getOutputStream()) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            process.destroy();
            throw e;
        }

        return process.waitFor();
    }

    private void processAudioFile(String fileName, double speedFactor) throws UnsupportedAudioFileException, IOException {
        File audioFile = new File(fileName);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFile);
        AudioFormat format = audioInputStream.getFormat();

        AudioDispatcher dispatcher = AudioDispatcherFactory.fromFile(audioFile, BUFFER_SIZE, 1);

        RateTransposer rateTransposer = new RateTransposer(speedFactor);
        WaveformWriter writerEdited = new WaveformWriter(format, fileName);

        dispatcher.addAudioProcessor(rateTransposer);
        dispatcher.addAudioProcessor(writerEdited);

        dispatcher.run();
    }

    private void applyEffects(String fileName) throws UnsupportedAudioFileException, IOException {
        File audioFile = new File(fileName);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFile);
        AudioFormat format = audioInputStream.getFormat();

        AudioDispatcher dispatcher = AudioDispatcherFactory.fromFile(audioFile, BUFFER_SIZE_SMALL, BUFFER_SIZE_SMALL - BUFFER_SIZE_SMALL_DIFF);

        PitchShifter pitchShifter = new PitchShifter(Double.parseDouble("0.85"), SAMPLE_RATE, BUFFER_SIZE_SMALL, BUFFER_SIZE_SMALL - BUFFER_SIZE_SMALL_DIFF);
        WaveformWriter writerEdited = new WaveformWriter(format, fileName);

        dispatcher.addAudioProcessor(pitchShifter);
        dispatcher.addAudioProcessor(new GainProcessor(GAIN));
        dispatcher.addAudioProcessor(writerEdited);
        dispatcher.run();
    }
}
