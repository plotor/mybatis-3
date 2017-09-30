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
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.io.Resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * @author Clinton Begin
 */
public class SerializedCache implements Cache {

    /** 序列化支持缓存装饰器 */

    /** 被装饰的 {@link Cache} 对象 */
    private final Cache delegate;

    public SerializedCache(Cache delegate) {
        this.delegate = delegate;
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
    public void putObject(Object key, Object object) {
        if (object == null || object instanceof Serializable) {
            // 缓存序列化后的结果
            delegate.putObject(key, this.serialize((Serializable) object));
        } else {
            throw new CacheException("SharedCache failed to make a copy of a non-serializable object: " + object);
        }
    }

    @Override
    public Object getObject(Object key) {
        Object object = delegate.getObject(key);
        // 如果缓存值存在，则执行反序列化
        return object == null ? null : this.deserialize((byte[]) object);
    }

    @Override
    public Object removeObject(Object key) {
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    /**
     * 序列化
     *
     * @param value
     * @return
     */
    private byte[] serialize(Serializable value) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(value);
            oos.flush();
            oos.close();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new CacheException("Error serializing object.  Cause: " + e, e);
        }
    }

    /**
     * 反序列化
     *
     * @param value
     * @return
     */
    private Serializable deserialize(byte[] value) {
        Serializable result;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(value);
            ObjectInputStream ois = new CustomObjectInputStream(bis);
            result = (Serializable) ois.readObject();
            ois.close();
        } catch (Exception e) {
            throw new CacheException("Error deserializing object.  Cause: " + e, e);
        }
        return result;
    }

    public static class CustomObjectInputStream extends ObjectInputStream {

        public CustomObjectInputStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            return Resources.classForName(desc.getName());
        }

    }

}
