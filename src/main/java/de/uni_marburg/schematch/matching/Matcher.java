package de.uni_marburg.schematch.matching;

import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.MatchStep;
import de.uni_marburg.schematch.matchtask.matchstep.MatchingStep;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import de.uni_marburg.schematch.utils.Configuration;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

@NoArgsConstructor
public abstract class Matcher {
    // this is required to make logs from abstract methods show the concrete class
    protected Logger getLogger() {
        return LogManager.getLogger(this.getClass());
    }

    /**
     * @param matchTask MatchTask to match
     * @param matchStep Current MatchStep (MatchingStep)
     * @return Similarity matrix for the given match task. Position (i,j) represents the similarity score for
     * the column pair (i-th source column, j-th target column)
     */
    public abstract float[][] match(MatchTask matchTask, MatchingStep matchStep);

    /**
     * Sets all matcher fields according to the configuration (see first_line_matchers.yaml)
     *
     * @param matcherConfiguration Configuration for this matcher
     */
    public void configure(Configuration.MatcherConfiguration matcherConfiguration) {
        Map<String, Object> params = matcherConfiguration.getParams();
        for (String key : params.keySet()) {
            Object value = params.get(key);
            Method setter;
            Class<?> fieldType;
            try {
                fieldType = configurationField(key).getType();
            } catch (NoSuchFieldException e) {
                getLogger().error("Configuration error: Could not find field " + key + " for " + this);
                throw new RuntimeException(e);
            }
            try {
                String setterName = "set" + key.substring(0, 1).toUpperCase() + key.substring(1);
                setter = this.getClass().getMethod(setterName, fieldType);
            } catch (NoSuchMethodException e) {
                getLogger().error("Configuration error: Could not find setter for field " + key + " for " + this);
                throw new RuntimeException(e);
            }
            try {
                Object converted = value;
                if (value instanceof Number number) {
                    if (fieldType == Float.class || fieldType == float.class) converted = number.floatValue();
                    else if (fieldType == Double.class || fieldType == double.class) converted = number.doubleValue();
                    else if (fieldType == Integer.class || fieldType == int.class) converted = number.intValue();
                    else if (fieldType == Long.class || fieldType == long.class) converted = number.longValue();
                }
                setter.invoke(this, converted);
            } catch (IllegalAccessException | InvocationTargetException e) {
                getLogger().error("Configuration error: Failed to invoke setter for field " + key + " for " + this);
                throw new RuntimeException(e);
            }
        }
    }

    /** Named algorithm adapters inherit transport/configuration fields from their bridge. */
    private Field configurationField(String name) throws NoSuchFieldException {
        for (Class<?> type = getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy; the public setter is resolved separately.
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(getClass().getSimpleName());
        result.append("(");
        for (Field field : getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                result.append(field.getName()).append("=").append(field.get(this)).append(", ");
                field.setAccessible(false);
            } catch (IllegalAccessException ignored) {
            } // Cannot happen, we have set the field to be accessible
        }
        String res = result.toString();
        if (getClass().getDeclaredFields().length > 0) {
            res = res.substring(0, res.length() - 2);
        }
        return res + ")";
    }
}
