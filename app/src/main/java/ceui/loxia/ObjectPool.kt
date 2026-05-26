package ceui.loxia

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.ModelObject
import ceui.lisa.models.NovelBean
import ceui.lisa.models.ObjectSpec
import ceui.lisa.models.UserBean
import java.io.Serializable
import kotlin.collections.set
import kotlin.reflect.KClass


data class ObjectKey(
    val id: Long,
    val type: Int
) : Serializable

/**
 * Object pool in Android is a software design pattern that involves reusing objects that are expensive to create or configure. It's essentially a collection of initialized objects that can be readily used by the application, reducing the overhead of creating new objects all the time.
 * */
object ObjectPool {

    val store = mutableMapOf<ObjectKey, MutableLiveData<Any>>()

    fun putUserPreview(preview: UserPreview) {
        preview.user?.let { user ->
            update(user)
        }

        preview.illusts?.forEach { illust ->
            update(illust)
        }
    }

    fun updateIllust(illust: IllustsBean) {
        update(illust)
        illust.user?.let { user ->
            update(user)
        }
    }

    /**
     * @param illustId The id of specified illustration
     * @return
     * */
    fun getIllust(illustId: Long): LiveData<IllustsBean> {
        return get(illustId)
    }

    fun getNovel(novelId: Long): LiveData<NovelBean> {
        return get(novelId)
    }

    fun updateUser(userBean: UserBean) {
        update(userBean)
    }

    fun followUser(userId: Long) {
        get<UserBean>(userId).value?.let { exist ->
            exist.isIs_followed = true
            update(exist)
        }
        get<User>(userId).value?.let { exist ->
            update(exist.copy(is_followed = true))
        }
    }

    fun unFollowUser(userId: Long) {
        get<UserBean>(userId).value?.let { exist ->
            exist.isIs_followed = false
            update(exist)
        }
        get<User>(userId).value?.let { exist ->
            update(exist.copy(is_followed = false))
        }
    }

    /**
     * @param id The id of the illustration
     * @return
     * */
    inline fun <reified ObjectT : ModelObject> get(id: Long): LiveData<ObjectT> {
        return getFromMap(ObjectT::class, id)
    }

    /**
     * @param objClass The data source
     * @param id The id of the illustration
     * @return
     * */
    fun <ObjectT : ModelObject> getFromMap(objClass: KClass<ObjectT>, id: Long): LiveData<ObjectT> {
        val key = ObjectKey(id, findObjectSpec(objClass))
        val storedLiveData = store[key]
        return (if (storedLiveData == null) {
            val newly = MutableLiveData<Any>()
            store[key] = newly
            newly
        } else {
            storedLiveData
        }) as LiveData<ObjectT>
    }

    inline fun <reified ObjectT : ModelObject> update(obj: ObjectT, isFullVersion: Boolean = false) {
        return updateObjectPool(obj, isFullVersion)
    }

    inline fun <reified ObjectT : ModelObject> updateObjectPool(obj: ObjectT, isFullVersion: Boolean) {
        val key = ObjectKey(obj.objectUniqueId, obj.objectType)
        val storedObject = store[key]
        if (storedObject == null) {
            store[key] = MutableLiveData(obj)
        } else {
            try {
                val lastValue = storedObject.value
                storedObject.value = if (isFullVersion || lastValue == null) {
                    obj
                } else {
                    mergeKeepingExisting(obj.javaClass, lastValue, obj)
                }
            } catch (ex: Exception) {
                storedObject.postValue(obj)
            }
        }
        Log.d("updateObjectPool", "对象池大小：${store.size}")
    }

    @PublishedApi
    internal val gson: Gson = Gson()

    /**
     * 列表接口返回的是「精简版」对象，往往缺少 detail 接口才有的字段（典型：caption）。
     * 池里已存在更完整的旧值时，新值只用来「补充」自己实际带值的字段，绝不让空/缺失的字段
     * 覆盖旧值的非空字段。这样后到的精简列表更新（如作者其他作品、用户作品列表）不会把
     * 已经展示出来的简介等抹掉。isFullVersion=true 的 detail 更新仍走整体覆盖。
     */
    @PublishedApi
    internal fun <T : Any> mergeKeepingExisting(clazz: Class<T>, old: Any, fresh: T): T {
        return try {
            val oldJson = gson.toJsonTree(old).asJsonObject
            val freshJson = gson.toJsonTree(fresh).asJsonObject
            for ((key, oldValue) in oldJson.entrySet()) {
                if (oldValue == null || oldValue.isJsonNull) continue
                val freshValue = freshJson.get(key)
                val freshIsBlank = freshValue == null || freshValue.isJsonNull ||
                    (freshValue.isJsonPrimitive && freshValue.asJsonPrimitive.isString && freshValue.asString.isEmpty()) ||
                    (freshValue.isJsonArray && freshValue.asJsonArray.size() == 0)
                if (freshIsBlank) {
                    freshJson.add(key, oldValue)
                }
            }
            gson.fromJson(freshJson, clazz) ?: fresh
        } catch (ex: Exception) {
            fresh
        }
    }

    private fun <ObjectT : ModelObject> findObjectSpec(objClass: KClass<ObjectT>): Int {
        val classSimpleName = objClass.simpleName ?: return ObjectSpec.UNKNOWN
        return when (classSimpleName) {
            "IllustsBean", "Novel" -> {
                ObjectSpec.POST
            }
            "Illust" -> {
                ObjectSpec.Illust
            }
            "UserBean" -> {
                ObjectSpec.USER
            }
            "User" -> {
                ObjectSpec.KUser
            }
            "Article" -> {
                ObjectSpec.ARTICLE
            }
            "GifInfoResponse" -> {
                ObjectSpec.GIF_INFO
            }
            "UserResponse" -> {
                ObjectSpec.UserProfile
            }
            else -> {
                ObjectSpec.UNKNOWN
            }
        }
    }
}