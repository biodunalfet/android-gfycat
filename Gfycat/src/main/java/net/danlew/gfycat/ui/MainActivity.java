package net.danlew.gfycat.ui;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import butterknife.ButterKnife;
import butterknife.InjectView;
import com.crashlytics.android.Crashlytics;
import net.danlew.gfycat.GfycatApplication;
import net.danlew.gfycat.Log;
import net.danlew.gfycat.R;
import net.danlew.gfycat.model.ConvertGif;
import net.danlew.gfycat.model.GfyMetadata;
import net.danlew.gfycat.model.UrlCheck;
import net.danlew.gfycat.service.GfycatService;
import rx.Observable;
import rx.Subscription;
import rx.android.observables.AndroidObservable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;

import javax.inject.Inject;

/**
 * This Activity takes a GIF URL and converts it to Gfycat.
 *
 * It's optimized to avoid having to restart the stream, so it handles its
 * own common configuration changes (e.g. orientation).
 */
public class MainActivity extends Activity implements ErrorDialog.IListener {

    private static final String INSTANCE_GFY_NAME = "INSTANCE_GFY_NAME";
    private static final String INSTANCE_GFY_METADATA = "INSTANCE_GFY_METADATA";
    private static final String INSTANCE_CURRENT_POSITION = "INSTANCE_CURRENT_POSITION";

    @Inject
    GfycatService mGfycatService;

    @InjectView(R.id.container)
    ViewGroup mContainer;

    @InjectView(R.id.progress_bar)
    ProgressBar mProgressBar;

    @InjectView(R.id.video_view)
    TextureView mVideoView;

    private String mGfyName;
    private GfyMetadata mGfyMetadata;
    private int mCurrentPosition;

    private MediaPlayer mMediaPlayer;

    private Subscription mLoadVideoSubscription;

    private BehaviorSubject<SurfaceTexture> mSurfaceTextureSubject = BehaviorSubject.create((SurfaceTexture) null);

    //////////////////////////////////////////////////////////////////////////
    // Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        GfycatApplication.get(this).inject(this);

        setContentView(R.layout.activity_main);

        ButterKnife.inject(this);

        // We want clicking the translucent background to quit, but not the video
        mVideoView.setClickable(true);
        mContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        mVideoView.setSurfaceTextureListener(mSurfaceTextureListener);

        // If this is an actual gfycat link, extract the name
        Uri data = getIntent().getData();
        if (data.getHost().endsWith("gfycat.com")) {
            mGfyName = data.getLastPathSegment();
        }

        if (savedInstanceState != null) {
            mGfyName = savedInstanceState.getString(INSTANCE_GFY_NAME);
            mGfyMetadata = savedInstanceState.getParcelable(INSTANCE_GFY_METADATA);
            mCurrentPosition = savedInstanceState.getInt(INSTANCE_CURRENT_POSITION);
        }

        // Fade in the background; looks nicer
        if (savedInstanceState == null) {
            mContainer.setAlpha(0);
            mContainer.animate().alpha(1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Begin a load, unless there was an error
        if (getFragmentManager().findFragmentByTag(ErrorDialog.TAG) == null) {
            loadGfy();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(INSTANCE_GFY_NAME, mGfyName);
        outState.putParcelable(INSTANCE_GFY_METADATA, mGfyMetadata);

        if (mMediaPlayer != null) {
            outState.putInt(INSTANCE_CURRENT_POSITION, mMediaPlayer.getCurrentPosition());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Reset the state of the entire Activity, prepare to reload everything in onStart()
        mLoadVideoSubscription.unsubscribe();

        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }

        mProgressBar.setVisibility(View.VISIBLE);
        // TODO: Figure out how to reset TextureViews
    }

    //////////////////////////////////////////////////////////////////////////
    // RxJava

    private Observable<String> getGfyNameObservable() {
        if (!TextUtils.isEmpty(mGfyName)) {
            return Observable.just(mGfyName);
        }

        final String url = getIntent().getData().toString();

        return mGfycatService.checkUrl(url)
            .flatMap(
                new Func1<UrlCheck, Observable<String>>() {
                    @Override
                    public Observable<String> call(UrlCheck urlCheck) {
                        if (urlCheck.isUrlKnown()) {
                            return Observable.just(urlCheck.getGfyName());
                        }

                        return mGfycatService.convertGif(url).map(new Func1<ConvertGif, String>() {
                            @Override
                            public String call(ConvertGif convertGif) {
                                return convertGif.getGfyName();
                            }
                        });
                    }
                }
            )
            .flatMap(
                new Func1<String, Observable<? extends String>>() {
                    @Override
                    public Observable<? extends String> call(String gfyName) {
                        // Error out if the name is empty
                        if (TextUtils.isEmpty(gfyName)) {
                            return Observable.error(new RuntimeException("Could not get gfyName for url: " + url));
                        }

                        return Observable.just(gfyName);
                    }
                }
            )
            .doOnNext(new Action1<String>() {
                @Override
                public void call(String gfyName) {
                    mGfyName = gfyName;
                }
            });
    }

    private Observable<GfyMetadata> getGfyMetadataObservable() {
        if (mGfyMetadata != null) {
            return Observable.just(mGfyMetadata);
        }

        return getGfyNameObservable()
            .flatMap(new Func1<String, Observable<? extends GfyMetadata>>() {
                @Override
                public Observable<? extends GfyMetadata> call(String gfyName) {
                    return mGfycatService.getMetadata(gfyName);
                }
            })
            .doOnNext(new Action1<GfyMetadata>() {
                @Override
                public void call(GfyMetadata gfyMetadata) {
                    mGfyMetadata = gfyMetadata;
                }
            });
    }

    private Observable<MediaPlayer> getLoadMediaPlayerObservable(Observable<GfyMetadata> gfyMetadataObservable) {
        return Observable.combineLatest(gfyMetadataObservable, mSurfaceTextureSubject,
            new Func2<GfyMetadata, SurfaceTexture, MediaPlayer>() {
                @Override
                public MediaPlayer call(GfyMetadata gfyMetadata, SurfaceTexture surfaceTexture) {
                    if (gfyMetadata == null || surfaceTexture == null) {
                        return null;
                    }

                    MediaPlayer mediaPlayer = new MediaPlayer();

                    mediaPlayer.setLooping(true);

                    mediaPlayer.setOnVideoSizeChangedListener(mAspectRatioListener);

                    mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            mProgressBar.setVisibility(View.GONE);
                            mp.start();
                            mp.seekTo(mCurrentPosition);
                        }
                    });

                    try {
                        mediaPlayer.setDataSource(gfyMetadata.getGfyItem().getWebmUrl());
                        mediaPlayer.setSurface(new Surface(mVideoView.getSurfaceTexture()));
                    }
                    catch (Exception e) {
                        throw new RuntimeException(e);
                    }

                    return mediaPlayer;
                }
            }
        )
            .filter(new Func1<MediaPlayer, Boolean>() {
                @Override
                public Boolean call(MediaPlayer mediaPlayer) {
                    return mediaPlayer != null;
                }
            })
            .doOnNext(new Action1<MediaPlayer>() {
                @Override
                public void call(MediaPlayer mediaPlayer) {
                    mMediaPlayer = mediaPlayer;
                }
            });
    }

    private void loadGfy() {
        Observable<GfyMetadata> gfyMetadataObservable = getGfyMetadataObservable();
        Observable<MediaPlayer> readyForDisplayObservable = getLoadMediaPlayerObservable(gfyMetadataObservable);

        mLoadVideoSubscription = AndroidObservable.bindActivity(this, readyForDisplayObservable)
            .subscribeOn(Schedulers.io())
            .subscribe(
                new Action1<MediaPlayer>() {
                    @Override
                    public void call(MediaPlayer mediaPlayer) {
                        try {
                            mediaPlayer.prepareAsync();
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                },
                new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        Log.e("Could not display GIF", throwable);
                        Crashlytics.logException(throwable);
                        new ErrorDialog().show(getFragmentManager(), "error");
                    }
                }
            );
    }

    //////////////////////////////////////////////////////////////////////////
    // Listeners

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mSurfaceTextureSubject.onNext(surface);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            correctVideoAspectRatio();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            mSurfaceTextureSubject.onNext(null);
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private MediaPlayer.OnVideoSizeChangedListener mAspectRatioListener = new MediaPlayer.OnVideoSizeChangedListener() {
        @Override
        public void onVideoSizeChanged(MediaPlayer mp, int dwidth, int dheight) {
            correctVideoAspectRatio();
        }
    };

    // We want to make sure the aspect ratio is correct; we can do that easily by scaling the TextureView
    // to the correct size.
    private void correctVideoAspectRatio() {
        if (mMediaPlayer == null) {
            return;
        }

        int dwidth = mMediaPlayer.getVideoWidth();
        int dheight = mMediaPlayer.getVideoHeight();

        float scaleX;
        float scaleY;

        // Reset scaling so we can do proper calculations
        mVideoView.setScaleX(1);
        mVideoView.setScaleY(1);

        // We want to figure out which dimension will fill; then scale the other one so it maintains aspect ratio
        int vwidth = mVideoView.getWidth();
        int vheight = mVideoView.getHeight();

        float ratioX = (float) vwidth / (float) dwidth;
        float ratioY = (float) vheight / (float) dheight;
        if (ratioX < ratioY) {
            scaleX = 1;
            float desiredHeight = ratioX * dheight;
            scaleY = desiredHeight / (float) vheight;
        }
        else {
            float desiredWidth = ratioY * dwidth;
            scaleX = desiredWidth / (float) vwidth;
            scaleY = 1;
        }

        mVideoView.setScaleX(scaleX);
        mVideoView.setScaleY(scaleY);
    }

    //////////////////////////////////////////////////////////////////////////
    // ErrorDialog.IListener

    @Override
    public void onDismiss() {
        finish();
    }
}
