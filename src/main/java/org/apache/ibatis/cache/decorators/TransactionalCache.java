/**
 * Copyright 2009-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * The 2nd level cache transactional buffer.
 *
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * 用于保存某个 {@link org.apache.ibatis.session.SqlSession} 需要向二级缓存添加的缓存数据
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

    private static final Log log = LogFactory.getLog(TransactionalCache.class);

    /** 被装饰的 {@link Cache} 对象（二级缓存） */
    private final Cache delegate;

    /** 是否提交事务时清空缓存 */
    private boolean clearOnCommit;

    /** 用于缓存数据，当提交事务时会将其中的数据写入二级缓存 */
    private final Map<Object, Object> entriesToAddOnCommit;

    /** 缓存未命中的 key */
    private final Set<Object> entriesMissedInCache;

    public TransactionalCache(Cache delegate) {
        this.delegate = delegate;
        this.clearOnCommit = false;
        this.entriesToAddOnCommit = new HashMap<Object, Object>();
        this.entriesMissedInCache = new HashSet<Object>();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }

    @Override
    public Object getObject(Object key) {
        // 先尝试从二级缓存中查询
        Object object = delegate.getObject(key);
        if (object == null) {
            // 二级缓存不命中，记录 key 到 entriesMissedInCache
            entriesMissedInCache.add(key);
        }
        if (clearOnCommit) {
            return null;
        } else {
            return object;
        }
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    @Override
    public void putObject(Object key, Object object) {
        // 缓存数据项到 entriesToAddOnCommit 中
        entriesToAddOnCommit.put(key, object);
    }

    @Override
    public Object removeObject(Object key) {
        return null;
    }

    @Override
    public void clear() {
        clearOnCommit = true;
        // 清空缓存待提交的数据项
        entriesToAddOnCommit.clear();
    }

    public void commit() {
        if (clearOnCommit) {
            delegate.clear();
        }
        this.flushPendingEntries();
        this.reset();
    }

    public void rollback() {
        this.unlockMissedEntries();
        this.reset();
    }

    private void reset() {
        clearOnCommit = false;
        entriesToAddOnCommit.clear();
        entriesMissedInCache.clear();
    }

    /**
     * 写本地缓存项到二级缓存
     */
    private void flushPendingEntries() {
        // 遍历 entriesToAddOnCommit 中的数据写入二级缓存
        for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
            delegate.putObject(entry.getKey(), entry.getValue());
        }
        // 遍历 entriesMissedInCache，将 entriesToAddOnCommit 不包含的缓存项对应二级缓存中的值置为 null
        for (Object entry : entriesMissedInCache) {
            if (!entriesToAddOnCommit.containsKey(entry)) {
                delegate.putObject(entry, null);
            }
        }
    }

    /**
     * 从二级缓存中移除缓存未命中的数据项
     */
    private void unlockMissedEntries() {
        for (Object entry : entriesMissedInCache) {
            try {
                delegate.removeObject(entry);
            } catch (Exception e) {
                log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
                        + "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
            }
        }
    }

}
