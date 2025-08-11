package io.hypersistence.utils.hibernate.type.json.internal;

import io.hypersistence.utils.common.LogUtils;
import io.hypersistence.utils.common.ReflectionUtils;
import io.hypersistence.utils.hibernate.type.util.ObjectMapperWrapper;
import org.hibernate.HibernateException;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.engine.jdbc.BinaryStream;
import org.hibernate.engine.jdbc.CharacterStream;
import org.hibernate.engine.jdbc.internal.ArrayBackedBinaryStream;
import org.hibernate.engine.jdbc.internal.CharacterStreamImpl;
import org.hibernate.models.spi.MemberDetails;
import org.hibernate.models.spi.TypeDetails;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.*;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators;
import org.hibernate.usertype.DynamicParameterizedType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.*;

/**
 * @author Vlad Mihalcea
 */
public class JsonJavaTypeDescriptor extends AbstractClassJavaType<Object> implements DynamicParameterizedType, JdbcTypeSetter {

    private Type propertyType;

    private Class propertyClass;

    private ObjectMapperWrapper objectMapperWrapper;

    private JdbcType jdbcType;

    public JsonJavaTypeDescriptor() {
        this(Object.class);
    }

    public JsonJavaTypeDescriptor(Type type) {
        this((Class<?>) type, ObjectMapperWrapper.INSTANCE);
    }

    public JsonJavaTypeDescriptor(Class clazz, final ObjectMapperWrapper objectMapperWrapper) {
        super(clazz, new MutableMutabilityPlan<>() {
            @Override
            protected Object deepCopyNotNull(Object value) {
                return objectMapperWrapper.clone(value);
            }
        });
        this.objectMapperWrapper = objectMapperWrapper;
        setPropertyClass(clazz);
    }

    public JsonJavaTypeDescriptor(final ObjectMapperWrapper objectMapperWrapper) {
        this(Object.class, objectMapperWrapper);
    }

    public JsonJavaTypeDescriptor(final ObjectMapperWrapper objectMapperWrapper, Type type) {
        this((Class) type, objectMapperWrapper);
        setPropertyClass(type);
    }

    @Override
    public void setParameterValues(Properties parameters) {
        Type type = null;
        final Object parameterType = parameters.get(PARAMETER_TYPE);
            if(parameterType instanceof ParameterType) {
            final MemberDetails xProperty = (MemberDetails) parameters.get(XPROPERTY);
            if (xProperty.getType().getTypeKind() == TypeDetails.Kind.TYPE_VARIABLE) {
                type = ((ParameterType) parameterType).getReturnedClass();
            } else {
                type = ((ParameterType) parameterType).getReturnedJavaType();
            }
            } else if(parameterType instanceof String) {
                type = ReflectionUtils.getClass((String) parameterType);
            }
        if(type == null) {
            throw new HibernateException("Could not resolve property type!");
        }
        setPropertyClass(type);
    }

    @Override
    public boolean areEqual(Object one, Object another) {
        if (one == another) {
            return true;
        }
        if (one == null || another == null) {
            return false;
        }
        if (one instanceof String && another instanceof String) {
            return one.equals(another);
        }
        if ((one instanceof Collection && another instanceof Collection) ||
            (one instanceof Map && another instanceof Map)) {
            return Objects.equals(one, another);
        }
        if (one.getClass().equals(another.getClass())) {
            var equalsMethod = ReflectionUtils.getMethodOrNull(one.getClass(), "equals", Object.class);
            if (equalsMethod != null && !Object.class.equals(equalsMethod.getDeclaringClass())) {
                return one.equals(another);
            }
        }
        return objectMapperWrapper.toJsonNode(objectMapperWrapper.toString(one)).equals(
            objectMapperWrapper.toJsonNode(objectMapperWrapper.toString(another))
        );
    }

    @Override
    public String toString(Object value) {
        return objectMapperWrapper.toString(value);
    }

    @Override
    public Object fromString(CharSequence string) {
        if(propertyClass == null) {
            throw new HibernateException(
                "The propertyClass in JsonTypeDescriptor is null, " +
                    "hence it doesn't know to what Java Object type " +
                    "to map the JSON column value that was read from the database!"
            );
        }
        if (String.class.isAssignableFrom(propertyClass)) {
            return string;
        }
        return objectMapperWrapper.fromString((String) string, propertyType);
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public <X> X unwrap(Object value, Class<X> type, WrapperOptions options) {
        if (value == null) {
            return null;
        }

        if (String.class.isAssignableFrom(type)) {
            return value instanceof String ? (X) value : (X) toString(value);
        } else if (BinaryStream.class.isAssignableFrom(type) ||
            byte[].class.isAssignableFrom(type)) {
            String stringValue = (value instanceof String) ? (String) value : toString(value);

            return (X) new ArrayBackedBinaryStream(DataHelper.extractBytes(new ByteArrayInputStream(stringValue.getBytes())));
        } else if (Blob.class.isAssignableFrom(type)) {
            String stringValue = (value instanceof String) ? (String) value : toString(value);

            if(options.getDialect() instanceof OracleDialect) {
                return (X) PrimitiveByteArrayJavaType.INSTANCE.unwrap(stringValue.getBytes(StandardCharsets.UTF_8), Blob.class, options);
            } else {
                return (X) BlobJavaType.INSTANCE.fromString(stringValue);
            }
        } else  if (Clob.class.isAssignableFrom(type)) {
            String stringValue = (value instanceof String) ? (String) value : toString(value);

            Clob clob = ClobJavaType.INSTANCE.wrap(stringValue, options);
            return (X) clob;
        } else if (CharacterStream.class.isAssignableFrom(type)) {
            String stringValue = (value instanceof String) ? (String) value : toString(value);

            return (X) new CharacterStreamImpl(stringValue);
        } if (Object.class.isAssignableFrom(type)) {
            String stringValue = (value instanceof String) ? (String) value : toString(value);
            return (X) objectMapperWrapper.toJsonNode(stringValue);
        }

        throw unknownUnwrap(type);
    }

    @Override
    public <X> Object wrap(X value, WrapperOptions options) {
        if (value == null) {
            return null;
        }

        String stringValue;
        if (value instanceof Map || value instanceof List) {
            stringValue = toString(value);
        } else {
            Blob blob = null;
            Clob clob = null;

            if (Blob.class.isAssignableFrom(value.getClass())) {
                blob = options.getLobCreator().wrap((Blob) value);
            }  if (Clob.class.isAssignableFrom(value.getClass())) {
                clob = options.getLobCreator().wrap((Clob) value);
            }  else if (byte[].class.isAssignableFrom(value.getClass())) {
                blob = options.getLobCreator().createBlob((byte[]) value);
            } else if (InputStream.class.isAssignableFrom(value.getClass())) {
                InputStream inputStream = (InputStream) value;
                try {
                    blob = options.getLobCreator().createBlob(inputStream, inputStream.available());
                } catch (IOException e) {
                    throw unknownWrap(value.getClass());
                }
            }

            try {
                if (blob != null) {
                    stringValue = new String(DataHelper.extractBytes(blob.getBinaryStream()));
                } else if (clob != null) {
                    stringValue = DataHelper.extractString(clob);
                } else {
                    stringValue = value.toString();
                }
            } catch (SQLException e) {
                throw new HibernateException("Unable to extract binary stream from Blob", e);
            }
        }

        try {
            return fromString(stringValue);
        } catch (HibernateException e) {
            stringValue = toString(value);
        }

        return fromString(stringValue);
    }

    private void setPropertyClass(Type type) {
        this.propertyType = type;
        if (type instanceof ParameterizedType) {
            type = ((ParameterizedType) type).getRawType();
        } else if (type instanceof TypeVariable) {
            type = ((TypeVariable) type).getGenericDeclaration().getClass();
        }
        this.propertyClass = (Class) type;
        validatePropertyType();
    }

    private void validatePropertyType() {
        if(Collection.class.isAssignableFrom(propertyClass)) {
            if (propertyType instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) propertyType;

                for(Class genericType : ReflectionUtils.getGenericTypes(parameterizedType)) {
                    synchronized (validatedTypes) {
                        if(!validatedTypes.add(genericType)) {
                            continue;
                        }
                    }
                    Method equalsMethod = ReflectionUtils.getMethodOrNull(genericType, "equals", Object.class);
                    Method hashCodeMethod = ReflectionUtils.getMethodOrNull(genericType, "hashCode");

                    if(equalsMethod == null ||
                        hashCodeMethod == null ||
                        Object.class.equals(equalsMethod.getDeclaringClass()) ||
                        Object.class.equals(hashCodeMethod.getDeclaringClass())) {
                        LogUtils.LOGGER.warn("The {} class should override both the equals and hashCode methods based on the JSON object value it represents!", genericType);
                    }
                }
            }
        }
    }

    @Override
    public JdbcType getRecommendedJdbcType(JdbcTypeIndicators indicators) {
        return jdbcType;
    }

    @Override
    public Class getJavaTypeClass() {
        return propertyClass;
    }

    public void setJdbcType(JdbcType jdbcType) {
        this.jdbcType = jdbcType;
    }

    private static final Set<Class> validatedTypes = new HashSet<>();
}
