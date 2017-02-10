/*******************************************************************************
 * The following code has been taken from
 * https://github.com/rupertlssmith/jenerator_utils/blob/master/util/src/main/java/com/thesett/util/hibernate/HibernateDetachUtil.java,
 * and is modified for use.
 * This modified code can be used freely. *
 *******************************************************************************/
package com.q4.hibernate.utils;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

import org.hibernate.Hibernate;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.proxy.HibernateProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unchecked")
public class HibernateDetachUtility {

	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(HibernateDetachUtility.class);

	public static enum SerializationType {
		SERIALIZATION, JAXB
	}

	public static void nullOutUninitializedFields(Object value) {
		long start = System.currentTimeMillis();
		Set<Integer> checkedObjs = new HashSet<>();
		try {
			nullOutUninitializedFields(value, checkedObjs, 0, SerializationType.SERIALIZATION);
			long duration = System.currentTimeMillis() - start;
			if (duration > 1000) {
				logger.info("Detached [" + checkedObjs.size() + "] objects in [" + duration + "]ms");
			} else {
				logger.debug("Detached [" + checkedObjs.size() + "] objects in [" + duration + "]ms");
			}
		} catch (Exception e) {
			logger.error("nullOutUninitializedFields", e);
		}

	}

	private static void nullOutUninitializedFields(Object value, Set<Integer> nulledObjects, int depth,
			SerializationType serializationType) throws IllegalArgumentException, IllegalAccessException, IntrospectionException {
		if (depth > 50) {
			logger.warn("The depth is too much " + value.getClass().getName());
			return;
		}
		if ((value == null) || nulledObjects.contains(System.identityHashCode(value))) {
			return;
		}
		nulledObjects.add(System.identityHashCode(value));
		if (value instanceof Object[]) {
			Object[] objArray = (Object[]) value;
			for (int i = 0; i < objArray.length; i++) {
				nullOutUninitializedFields(objArray[i], nulledObjects, depth + 1, serializationType);
			}
		} else if (value instanceof Collection) {
			for (Object val : (Collection) value) {
				nullOutUninitializedFields(val, nulledObjects, depth + 1, serializationType);
			}
		} else if (value instanceof Map) {
			for (Object key : ((Map) value).keySet()) {
				nullOutUninitializedFields(((Map) value).get(key), nulledObjects, depth + 1, serializationType);
				nullOutUninitializedFields(key, nulledObjects, depth + 1, serializationType);
			}
		}
		if (serializationType == SerializationType.JAXB) {
			XmlAccessorType at = value.getClass().getAnnotation(XmlAccessorType.class);
			if (at != null && at.value() == XmlAccessType.FIELD) {
				initFieldByFieldAccess(value, nulledObjects, depth, serializationType);
			} else {
				initFields(value, nulledObjects, depth, serializationType);
			}
		} else if (serializationType == SerializationType.SERIALIZATION) {
			initFieldByFieldAccess(value, nulledObjects, depth, serializationType);
		}
	}

	private static void initFieldByFieldAccess(Object object, Set<Integer> nulledObjects, int depth,
			SerializationType serializationType) throws IllegalArgumentException, IllegalAccessException, IntrospectionException {
		Class tmpClass = object.getClass();
		List<Field> fieldsToClean = new ArrayList<Field>();
		while (tmpClass != null && tmpClass != Object.class) {
			Collections.addAll(fieldsToClean, tmpClass.getDeclaredFields());
			tmpClass = tmpClass.getSuperclass();
		}
		initFieldByFieldAccess(object, fieldsToClean, nulledObjects, depth, serializationType);
	}

	@SuppressWarnings("unchecked")
	private static void initFieldByFieldAccess(Object object, List<Field> classFields, Set<Integer> nulledObjects, int depth,
			SerializationType serializationType) throws IllegalArgumentException, IllegalAccessException, IntrospectionException {
		boolean accessModifierFlag = false;
		for (Field field : classFields) {
			accessModifierFlag = false;
			if (!field.isAccessible()) {
				field.setAccessible(true);
				accessModifierFlag = true;
			}
			Object fieldValue = field.get(object);
			if (fieldValue instanceof HibernateProxy) {
				Object replacement = null;
				if (fieldValue.getClass().getName().contains("javassist")) {
					Class assistClass = fieldValue.getClass();
					try {
						Method m = assistClass.getMethod("writeReplace");
						replacement = m.invoke(fieldValue);
						String className = fieldValue.getClass().getName();
						className = className.substring(0, className.indexOf("_$$_"));
						if (!replacement.getClass().getName().contains("hibernate")) {
							nullOutUninitializedFields(replacement, nulledObjects, depth + 1, serializationType);
							field.set(object, replacement);
						} else {
							replacement = null;
						}
					} catch (Exception e) {
						logger.error("Unable to write replace object " + fieldValue.getClass(), e);
					}
				}
				if (replacement == null) {
					field.set(object, replacement);

				}
			} else {
				if (fieldValue instanceof PersistentCollection) {
					if (!((PersistentCollection) fieldValue).wasInitialized()) {
						field.set(object, null);
					} else {
						Object replacement = null;
						if (fieldValue instanceof Map) {
							replacement = new HashMap((Map) fieldValue);
						} else if (fieldValue instanceof List) {
							replacement = new ArrayList((List) fieldValue);
						} else if (fieldValue instanceof Set) {
							replacement = new HashSet((Set) fieldValue);
						} else if (fieldValue instanceof Collection) {
							replacement = new ArrayList((Collection) fieldValue);
						}
						setFieldValue(object, field.getName(), replacement);
						nullOutUninitializedFields(replacement, nulledObjects, depth + 1, serializationType);
					}
				} else {
					if (fieldValue != null && (fieldValue.getClass().getName().contains("com.q4") || fieldValue instanceof Collection
							|| fieldValue instanceof Object[] || fieldValue instanceof Map))
						nullOutUninitializedFields((fieldValue), nulledObjects, depth + 1, serializationType);
				}
			}
			if (accessModifierFlag) {
				field.setAccessible(false);
			}
		}
	}

	private static void initFields(Object value, Set<Integer> nulledObjects, int depth, SerializationType serializationType)
			throws IntrospectionException, IllegalArgumentException, IllegalAccessException {
		BeanInfo bi = Introspector.getBeanInfo(value.getClass(), Object.class);
		PropertyDescriptor[] pds = bi.getPropertyDescriptors();
		for (PropertyDescriptor pd : pds) {
			Object propertyValue = null;
			try {
				propertyValue = pd.getReadMethod().invoke(value);
			} catch (Exception e) {
				if (logger.isDebugEnabled()) {
					logger.debug(pd.getName() + " --> " + value.getClass().getSimpleName(), e);
				}
			}
			if (!Hibernate.isInitialized(propertyValue)) {
				try {
					if (logger.isDebugEnabled()) {
						logger.debug(pd.getName() + "--> " + value.getClass().getSimpleName());
					}
					Method writeMethod = pd.getWriteMethod();
					if ((writeMethod != null) && (writeMethod.getAnnotation(XmlTransient.class) == null)) {
						pd.getWriteMethod().invoke(value, new Object[] {null});
					} else {
						setValueToNull(value, pd.getName());
					}
				} catch (Exception e) {
					logger.debug("Error while access" + pd.getName() + "--> " + value.getClass().getSimpleName(), e);
					setValueToNull(value, pd.getName());
				}
			} else {
				if ((propertyValue instanceof Collection)
						|| ((propertyValue != null) && propertyValue.getClass().getName().startsWith("com.q4.qhix"))) {
					nullOutUninitializedFields(propertyValue, nulledObjects, depth + 1, serializationType);
				}
			}
		}
	}

	private static void setFieldValue(Object object, String fieldName, Object newValue) {
		try {
			Field f = object.getClass().getDeclaredField(fieldName);
			if (f != null) {
				f.setAccessible(true);
				f.set(object, newValue);
			}
		} catch (NoSuchFieldException e) {
			logger.warn("setField", e);
		} catch (IllegalAccessException e) {
			logger.warn("setField", e);
		}
	}

	private static void setValueToNull(Object value, String fieldName) {
		try {
			Field f = value.getClass().getDeclaredField(fieldName);
			if (f != null) {
				f.setAccessible(true);
				f.set(value, null);
			}
		} catch (NoSuchFieldException e) {
			logger.warn("nullOutField", e);
		} catch (IllegalAccessException e) {
			logger.warn("nullOutField", e);
		}
	}
}
