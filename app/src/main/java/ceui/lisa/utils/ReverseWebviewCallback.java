package ceui.lisa.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AppCompatActivity;
import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.TemplateActivity;
import io.reactivex.disposables.Disposable;
import java.io.IOException;
import okhttp3.ResponseBody;
import retrofit2.Response;

public class ReverseWebviewCallback implements ReverseImage.Callback {

    private final Context mContext;
    private Uri imageUri;

    public ReverseWebviewCallback(Context context) {
        mContext = context;
    }

    public ReverseWebviewCallback(Context context, Uri imageUri) {
        mContext = context;
        this.imageUri = imageUri;
    }

    @Override
    public void onSubscribe(Disposable d) {
        Common.showToast("Loading");
    }

    @Override
    public void onNext(Response<ResponseBody> response) {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "以图搜图");
        intent.putExtra(Params.REVERSE_SEARCH_RESULT, new ReverseResult(response));
        intent.putExtra(Params.REVERSE_SEARCH_IMAGE_URI, this.imageUri);
        mContext.startActivity(intent);
        if (!(mContext instanceof MainActivity)) {
            ((AppCompatActivity) mContext).finish();
        }
    }

    @Override
    public void onError(Throwable e) {
        e.printStackTrace();
        if (e instanceof IOException) {
            Common.showToast(mContext.getString(R.string.reverse_image_network_error));
        } else {
            Common.showToast(e.getMessage());
        }
    }
}
