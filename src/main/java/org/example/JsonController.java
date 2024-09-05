package org.example;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.GainProcessor;
import be.tarsos.dsp.PitchShifter;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.io.jvm.WaveformWriter;
import be.tarsos.dsp.resample.RateTransposer;
import org.springframework.web.bind.annotation.*;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.core.*;

@RestController
@RequestMapping("/api/tts")
@Produces(MediaType.APPLICATION_JSON)
public class JsonController {

    private static final int BUFFER_SIZE = 4096;

    private static final int BUFFER_SIZE_SMALL = 512;
    private static final int BUFFER_SIZE_SMALL_DIFF = 32;
    private static final double GAIN = 3;
    private static final int SAMPLE_RATE = 22050;

    @SuppressWarnings("unchecked")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({"audio/wav", MediaType.TEXT_PLAIN})
    public Response create(@RequestBody RequestBodyModel requestBody, @Context final HttpHeaders headers, @Context final Request req) {
        try {

            String outputFilePath = UUID.randomUUID() + ".wav";
            int exitCode = processConversion(requestBody.getText(), outputFilePath);

            if (exitCode != 0) {
                return buildErrorResponse("Error during TTS conversion");
            }

            processAudioFile(outputFilePath, Double.parseDouble("0.95"));
            applyEffects(outputFilePath);

            return buildResponse(outputFilePath, headers);

        } catch (Exception e) {
            return buildErrorResponse("Exception occurred: " + e.getMessage());
        }
    }

    private Response buildResponse(String outputFilePath, HttpHeaders headers) {
        String acceptHeader = headers.getHeaderString(HttpHeaders.ACCEPT);
        if (MediaType.TEXT_PLAIN.equals(acceptHeader)) {
            return Response.ok(new File(outputFilePath).getName(), MediaType.TEXT_PLAIN).build();
        }

        File file = new File(outputFilePath);
        if (!file.exists()) {
            return buildErrorResponse("Audio file not found");
        }

        return Response.ok(file, "audio/wav")
                .header("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"")
                .build();
    }

    private Response buildErrorResponse(String message) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(message)
                .type(MediaType.TEXT_PLAIN)
                .build();
    }

    @SuppressWarnings("squid:S4721")
    private int processConversion(String text, String outputFilePath) throws IOException, InterruptedException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Text cannot be null or empty.");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                "etc/resources/piper/piper",
                "--model", "etc/resources/piper/cs_CZ-jirka-medium.onnx",
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
