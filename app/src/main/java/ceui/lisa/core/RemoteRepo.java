package ceui.lisa.core;

import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

/**
 * The class stores response got from remote repo (pixiv) in the form of {@link ListShow}
 * */
public abstract class RemoteRepo<Response extends ListShow<?>> extends BaseRepo {

    /**
     * In the context of Android and RxJava, ? extends Response refers to a generic type used with Observables. Here's a breakdown:
     * <p>
     * ?: This symbol represents a wildcard. It indicates that the specific type of object the Observable emits is unknown, but it's guaranteed to be a subtype of Response.
     * extends Response: This part specifies that the type can be either the Response class itself or any class that inherits from Response. In other words, the Observable can emit objects of any type as long as that type is a subclass of Response.
     * */
    private Observable<? extends Response> mApi;
    protected String nextUrl = "";

    public RemoteRepo() {
        // 不在构造器里缓存 mapper()。
        // Kotlin 子类属性初始化（如 filterMapper = null）发生在 super() 之后，
        // 若这里提前调用 mapper() 并把结果缓存，子类随后的字段初始化会把
        // filterMapper 清回 null，导致 update() 里 filterMapper?.xxx 全部 no-op
        // （「喜欢！数」本地 total_bookmarks 过滤失效，个位数仍显示）。
        // 改为每次请求时调用 mapper()，让子类在完整构造后同步门槛。
    }

    /**
     * An interface overrided in different class to init different Api depending on the response type
     * <p>
     * For expample:
     * <p>
     * The remote repo of the homepage list is {@link ceui.lisa.model.RecmdIllust}
     * <p>
     * While the remote repo of a legacy list page is a concrete RemoteRepo subclass
     * */
    public abstract Observable<? extends Response> initApi();

//    /**
//     * Early development,it only returns JSON Array now
//     * */
//    public abstract Observable<? extends Response> initLofterApi();

    public abstract Observable<? extends Response> initNextApi();

    /**
     * Init Api and POST request to get response containing information of illustrations
     * @param nullCtrl (In doubt)In case of null
     * */
    public void getFirstData(NullCtrl<Response> nullCtrl) {
        mApi = initApi();//mApi contains the response data
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .map(mapper())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(nullCtrl);
        }
    }

//    public void getLofterFirstData(NullCtrl<Response> nullCtrl) {
//        mApi = initLofterApi();//mApi contains the response data
//        if (mApi != null) {
//            mApi.subscribeOn(Schedulers.newThread())
//                    .map(mapper())
//                    .observeOn(AndroidSchedulers.mainThread())
//                    .subscribe(nullCtrl);
//        }
//    }

    public void getNextData(NullCtrl<Response> nullCtrl) {
        mApi = initNextApi();
        if (mApi != null) {
            mApi.subscribeOn(Schedulers.newThread())
                    .map(mapper())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(nullCtrl);
        }
    }

    public Function<? super Response, Response> mapper() {
        return new Mapper<>();
    }

    public String getNextUrl() {
        return nextUrl;
    }

    public void setNextUrl(String nextUrl) {
        this.nextUrl = nextUrl;
    }

    public boolean hasEffectiveUserFollowStatus() {
        return true;
    }
}
