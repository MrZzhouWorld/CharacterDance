package com.liu.kinton.CharacterDance.utils;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.api.android.AndroidSequenceEncoder;
import org.jcodec.common.AndroidUtil;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rational;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoUtils {

    static private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    private static final boolean VERBOSE = false;
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private static Bitmap getBitmapBySec(MediaExtractor extractor, MediaFormat mediaFormat, MediaCodec decoder, long sec) throws IOException {

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Bitmap bitmap = null;
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        boolean stopDecode = false;
        final int width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
        final int height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
        Log.i("getBitmapBySec", "w: " + width);
        long presentationTimeUs = -1;
        int outputBufferId;
        Image image = null;

        extractor.seekTo(sec, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                //Log.i("getBitmapBySec", "sawInputEOS: " + sawInputEOS);
                int inputBufferId = decoder.dequeueInputBuffer(-1);
                //Log.i("getBitmapBySec", "inputBufferId: " + inputBufferId);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        //Log.i("getBitmapBySec", "sampleSize<0 ");
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        presentationTimeUs = extractor.getSampleTime();
                        //Log.i("getBitmapBySec", "presentationTimeUs: " + presentationTimeUs);
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }
            outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            if (outputBufferId >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 | presentationTimeUs >= sec) {
                    Log.i("getBitmapBySec", "sec: " + sec);
                    sawOutputEOS = true;
                    boolean doRender = (info.size != 0);
                    if (doRender) {
                        Log.i("getBitmapBySec", "deal bitmap which at " + presentationTimeUs);
                        image = decoder.getOutputImage(outputBufferId);
                        YuvImage yuvImage = new YuvImage(YUV_420_888toNV21(image), ImageFormat.NV21, width, height, null);

                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, stream);
                        bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size());
                        stream.close();
                        image.close();
                    }
                }
                decoder.releaseOutputBuffer(outputBufferId, true);
            }
        }

        return bitmap;
    }

    public static Bitmap getBitmapByUri(Context context,Uri uri){
        String path = FileUtils.getPathByUri(context,uri);
        File file = new File(path);
        MediaExtractor extractor = null;
        MediaFormat mediaFormat = null;
        MediaCodec decoder = null;
        Bitmap bitmap =null;
        try{
            extractor = initMediaExtractor(file);
            mediaFormat = initMediaFormat(path, extractor);
            decoder = initMediaCodec(mediaFormat);
            decoder.configure(mediaFormat, null, null, 0);
            decoder.start();

            bitmap= getBitmapBySec(extractor, mediaFormat, decoder, 0l);
        }catch (IOException ex){

        }
        return  bitmap;
    }

    private static byte[] YUV_420_888toNV21(Image image) {
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];
        if (VERBOSE) Log.v("YUV_420_888toNV21", "get data from " + planes.length + " planes");
        int channelOffset = 0;
        int outputStride = 1;
        for (int i = 0; i < planes.length; i++) {
            switch (i) {
                case 0:
                    channelOffset = 0;
                    outputStride = 1;
                    break;
                case 1:
                    channelOffset = width * height + 1;
                    outputStride = 2;

                    break;
                case 2:
                    channelOffset = width * height;
                    outputStride = 2;

                    break;
            }
            ByteBuffer buffer = planes[i].getBuffer();
            int rowStride = planes[i].getRowStride();
            int pixelStride = planes[i].getPixelStride();
            if (VERBOSE) {
                Log.v("YUV_420_888toNV21", "pixelStride " + pixelStride);
                Log.v("YUV_420_888toNV21", "rowStride " + rowStride);
                Log.v("YUV_420_888toNV21", "width " + width);
                Log.v("YUV_420_888toNV21", "height " + height);
                Log.v("YUV_420_888toNV21", "buffer size " + buffer.remaining());
            }
            int shift = (i == 0) ? 0 : 1;
            int w = width >> shift;
            int h = height >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            for (int row = 0; row < h; row++) {
                int length;
                if (pixelStride == 1 && outputStride == 1) {
                    length = w;
                    buffer.get(data, channelOffset, length);
                    channelOffset += length;
                } else {
                    length = (w - 1) * pixelStride + 1;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[channelOffset] = rowData[col * pixelStride];
                        channelOffset += outputStride;
                    }
                }
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }
            if (VERBOSE) Log.v("", "Finished reading data from plane " + i);
        }
        return data;
    }

    private static long getValidSampleTime(long time, MediaExtractor extractor, MediaFormat format) {
        extractor.seekTo(time, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        int count = 0;
        int maxRange = 5;
        long sampleTime = extractor.getSampleTime();
        while (count < maxRange) {
            extractor.advance();
            long s = extractor.getSampleTime();
            if (s != -1L) {
                count++;
                // 选取和目标时间差值最小的那个
                sampleTime = getMinDiffTime(time, sampleTime, s);
                if (Math.abs(sampleTime - time) <= format.getInteger(MediaFormat.KEY_FRAME_RATE)) {
                    //如果这个差值在 一帧间隔 内，即为成功
                    return sampleTime;
                }
            } else {
                count = maxRange;
            }
        }
        return sampleTime;
    }

    private static long getMinDiffTime(long time, long value1, long value2) {
        long diff1 = value1 - time;
        long diff2 = value2 - time;
        diff1 = diff1 > 0 ? diff1 : -diff1;
        diff2 = diff2 > 0 ? diff2 : -diff2;
        return diff1 < diff2 ? value1 : value2;
    }


    static class VideoInfo {
        long time;
        int width;
        int height;
    }

    public static VideoInfo getVideoInfo(Context context, String path) {
        File file = new File(path);
        MediaPlayer mediaPlayer = getVideoMediaPlayer(context, file);
        VideoInfo vi = new VideoInfo();
        vi.time = mediaPlayer == null ? 0 : mediaPlayer.getDuration();
        vi.height = mediaPlayer == null ? 0 : mediaPlayer.getVideoHeight();
        vi.width = mediaPlayer == null ? 0 : mediaPlayer.getVideoWidth();
        mediaPlayer.release();
        return vi;
    }

    static public boolean initFrameFromVideoBySecond(Context context, String savePath, String
            videoPath, int width, int height, long duration) {
        File file = new File(videoPath);
        MediaExtractor extractor = null;
        MediaFormat mediaFormat = null;
        MediaCodec decoder = null;

        boolean res = false;
        try {
            long totalSec = duration * 1000;

            Log.i("totalSec", "totalSec:" + totalSec);
            for (long time = 0; time < totalSec; time += 200000) {
                //获取这一帧图片
                extractor = initMediaExtractor(file);
                mediaFormat = initMediaFormat(videoPath, extractor);
                decoder = initMediaCodec(mediaFormat);
                decoder.configure(mediaFormat, null, null, 0);
                decoder.start();

                Bitmap bitmap = getBitmapBySec(extractor, mediaFormat, decoder, time);
                if (bitmap == null) continue;

                Log.i("initFrameFromVideoBySecond", "w: " + bitmap.getWidth());

                float xScale = (float) 100 / bitmap.getWidth();

                Log.i("initFrameFromVideoBySecond", "xScale: " + xScale);

                bitmap = BitmapUtils.compressBitmap(bitmap, xScale, xScale);

                bitmap = BitmapUtils.array2Bitmap(BitmapUtils.getBitmap2GaryArray(bitmap), bitmap.getWidth(), bitmap.getHeight());
                BitmapUtils.addGraphToGallery(context, bitmap, "FunVideo_CachePic_Source", false);
                bitmap.recycle();

                decoder.stop();
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
                if (extractor != null) {
                    extractor.release();
                }
            }

            res = true;
        } catch (IOException ex) {
            Log.i("init error", ex.getMessage());
            ex.printStackTrace();
        } finally {

        }
        return res;
    }

    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d("selectTrack", "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }
        return -1;
    }

    static public MediaCodec initMediaCodec(MediaFormat mediaFormat) throws IOException {
        MediaCodec decoder = null;
        String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
        decoder = MediaCodec.createDecoderByType(mime);
        showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
        if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
            Log.i("initMediaCodec", "set decode color format to type " + decodeColorFormat);
        } else {
            Log.i("initMediaCodec", "unable to set decode color format, color format type " + decodeColorFormat + " not supported");
        }
        return decoder;
    }

    static private MediaFormat initMediaFormat(String path, MediaExtractor extractor) {
        int trackIndex = selectTrack(extractor);
        if (trackIndex < 0) {
            throw new RuntimeException("No video track found in " + path);
        }
        extractor.selectTrack(trackIndex);
        MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
        return mediaFormat;
    }

    static private MediaExtractor initMediaExtractor(File path) throws IOException {
        MediaExtractor extractor = null;
        extractor = new MediaExtractor();
        extractor.setDataSource(path.toString());
        return extractor;
    }

    static private void showSupportedColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        System.out.print("supported color format: ");
        for (int c : caps.colorFormats) {
            System.out.print(c + "\t");
        }
        System.out.println();
    }

    static private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.
            CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) {
                return true;
            }
        }
        return false;
    }

    static public String convertVideoBySourcePics(Context context, String picsDri) {
        SeekableByteChannel out = null;
        File destDir = new File(Environment.getExternalStorageDirectory() + "/FunVideo_Video");
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        File file = new File(destDir.getPath() + "/funvideo_" + System.currentTimeMillis() + ".mp4");
        try {
            file.createNewFile();
            // for Android use: AndroidSequenceEncoder
            File _piscDri = new File(picsDri);
            AndroidSequenceEncoder encoder = AndroidSequenceEncoder.createSequenceEncoder(file, 5);
            for (File childFile : _piscDri.listFiles()) {
                Bitmap bitmap = BitmapUtils.getBitmapByUri(context, Uri.fromFile(childFile));
                encoder.encodeImage(bitmap);
                bitmap.recycle();
            }
            encoder.finish();
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            Uri contentUri = Uri.fromFile(file);
            mediaScanIntent.setData(contentUri);
            context.sendBroadcast(mediaScanIntent);
            Log.i("addGraphToGallery", "ok");

        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            NIOUtils.closeQuietly(out);
        }
        return file.getPath();
    }

    private static MediaPlayer getVideoMediaPlayer(Context context, File file) {
        try {
            return MediaPlayer.create(context, Uri.fromFile(file));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
