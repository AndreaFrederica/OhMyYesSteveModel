package com.elfmcys.ysm.config.util;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public class ConfigBuilder {
    private static final Map<Configuration, Map<String, Object>> CONFIG_DEFAULT_VALUES = new HashMap<>();
    private final Map<String, Object> defaultValues;
    private final Configuration config;
    private final boolean writeProp;
    private final LinkedList<String> langKeyPath = new LinkedList<>();
    private IFormatter langKeyFormatter = IFormatter.LOWER_CASE;
    private String langKeyPrefix = "";
    private ConfigCategory currentCategory;

    /**
     * Reads values from the config file into currentValue.
     */
    public static ConfigBuilder startReadingFromFile(Configuration config) {
        config.load();
        return new ConfigBuilder(config, false);
    }

    /**
     * Reads values from Property into currentValue.
     * Call this from {@link net.minecraftforge.fml.client.event.ConfigChangedEvent.OnConfigChangedEvent},
     * when Property already has the new values, but currentValue and the config file do not.
     */
    public static ConfigBuilder startReadingFromProp(Configuration config) {
        return new ConfigBuilder(config, false);
    }

    /**
     * Writes currentValue to Property and the config file.
     */
    public static ConfigBuilder startSaving(Configuration config) {
        return new ConfigBuilder(config, true);
    }

    /**
     * @param writeProp whether to write currentValue to Property.
     */
    private ConfigBuilder(Configuration config, boolean writeProp) {
        this.defaultValues = Optional.ofNullable(CONFIG_DEFAULT_VALUES.get(config)).orElse(new HashMap<>());
        this.config = config;
        this.writeProp = writeProp;
    }

    public void finishBuilding() {
        this.assertClean();
        CONFIG_DEFAULT_VALUES.put(this.config, this.defaultValues);
        if (this.config.hasChanged()) this.config.save();
    }

    private void assertClean() {
        if (this.currentCategory != null) {
            throw new IllegalStateException("Unclosed config category: " + this.currentCategory.getQualifiedName());
        }
        if (!this.langKeyPath.isEmpty()) {
            throw new IllegalStateException("Unclosed lang key path: " + String.join(".", this.langKeyPath));
        }
    }

    public void setLangKeyFormatter(IFormatter formatter) {
        this.langKeyFormatter = formatter;
    }

    public String formatLangKey(String str) {
        return this.langKeyFormatter.format(str);
    }

    /**
     * Sets the langKey prefix, such as config.modid.
     *
     * @param langKeyPrefix without a trailing ".".
     */
    public void setLangKeyPrefix(@Nullable String langKeyPrefix) {
        this.langKeyPrefix = StringUtils.isBlank(langKeyPrefix) ? "" : this.formatLangKey(langKeyPrefix);
    }

    public void pushLangKey(String langKey) {
        this.langKeyPath.add(this.formatLangKey(langKey));
    }

    /**
     * Pops one langKey level.
     *
     * @return the popped langKey
     */
    @Nullable
    public String popLangKey() {
        return this.langKeyPath.pollLast();
    }

    /**
     * Gets the current path langKey.
     * For example, config.modid.path.
     */
    public String getCurrentLangKey() {
        if (this.langKeyPath.isEmpty()) return this.langKeyPrefix;
        String path = String.join(".", this.langKeyPath);
        return this.langKeyPrefix.isEmpty() ? path : this.langKeyPrefix + "." + path;
    }

    /**
     * Gets a child langKey under the current path.
     * If {@link #getCurrentLangKey()} returns config.modid.path, this returns config.modid.path.name.
     */
    public String getCurrentSubLangKey(String name) {
        String current = this.getCurrentLangKey();
        name = this.formatLangKey(name);
        return current.isEmpty() ? name : current + "." + name;
    }

    /**
     * Pushes one category level.
     */
    public void pushCategory(String category, @Nullable String comment, @Nullable String langKey) {
        ConfigCategory newCategory = this.config.getCategory(this.currentCategory != null ? this.currentCategory.getQualifiedName() + Configuration.CATEGORY_SPLITTER + category : category);
        if (StringUtils.isNoneBlank(comment)) {
            newCategory.setComment(comment);
        }
        if (StringUtils.isNoneBlank(langKey)) {
            this.pushLangKey(langKey);
            newCategory.setLanguageKey(this.getCurrentLangKey());
        }
        this.currentCategory = newCategory;
    }

    /**
     * Pushes one category level and uses its name as one langKey level.
     */
    public void pushCategory(String category, @Nullable String comment) {
        this.pushCategory(category, comment, category);
    }

    /**
     * Pushes one category level and uses its name as one langKey level.
     */
    public void pushCategory(String category) {
        this.pushCategory(category, null);
    }

    /**
     * Pops one category level and one langKey level.
     *
     * @return the popped category
     */
    @Nullable
    public ConfigCategory popCategory() {
        ConfigCategory pop = this.popCategoryWithoutLangKey();
        this.popLangKey();
        return pop;
    }

    /**
     * Pops one category level.
     *
     * @return the popped category
     */
    @Nullable
    public ConfigCategory popCategoryWithoutLangKey() {
        if (this.currentCategory == null) return null;
        ConfigCategory pop = this.currentCategory;
        this.currentCategory = this.currentCategory.parent;
        return pop;
    }

    @Nullable
    public ConfigCategory getCurrentCategory() {
        return this.currentCategory;
    }

    /*
    Prop handling
     */

    private String getCurrentCategoryForValue() {
        ConfigCategory current = this.getCurrentCategory();
        if (current == null) throw new IllegalStateException("No category!");
        return current.getQualifiedName();
    }

    @SuppressWarnings("unchecked")
    private <T> T loadDefault(String name, T currentValue) {
        String key = this.getCurrentCategoryForValue() + "." + name;
        return (T) this.defaultValues.computeIfAbsent(key, k -> currentValue);
    }

    // String

    /**
     * @param langKey full langKey, unaffected by the current pushed path.
     */
    public Property getProp(String name, String currentValue, @Nullable String comment, @Nullable String langKey) {
        Property prop = this.config.get(this.getCurrentCategoryForValue(), name, this.loadDefault(name, currentValue), comment);
        if (StringUtils.isNoneBlank(langKey)) prop.setLanguageKey(langKey);
        if (this.writeProp) prop.set(currentValue);
        return prop;
    }

    public Property getProp(String name, String currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment, this.getCurrentSubLangKey(name));
    }

    public Property getProp(String name, String currentValue) {
        return this.getProp(name, currentValue, null);
    }

    public String get(String name, String currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment).getString();
    }

    public String get(String name, String currentValue) {
        return this.get(name, currentValue, null);
    }

    // boolean

    /**
     * @param langKey full langKey, unaffected by the current pushed path.
     */
    public Property getProp(String name, boolean currentValue, @Nullable String comment, @Nullable String langKey) {
        Property prop = this.config.get(this.getCurrentCategoryForValue(), name, this.loadDefault(name, currentValue), comment);
        if (StringUtils.isNoneBlank(langKey)) prop.setLanguageKey(langKey);
        if (this.writeProp) prop.set(currentValue);
        return prop;
    }

    public Property getProp(String name, boolean currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment, this.getCurrentSubLangKey(name));
    }

    public Property getProp(String name, boolean currentValue) {
        return this.getProp(name, currentValue, null);
    }

    public boolean get(String name, boolean currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment).getBoolean();
    }

    public boolean get(String name, boolean currentValue) {
        return this.get(name, currentValue, null);
    }

    // int

    /**
     * @param langKey full langKey, unaffected by the current pushed path.
     */
    public Property getProp(String name, int currentValue, @Nullable String comment, @Nullable String langKey) {
        Property prop = this.config.get(this.getCurrentCategoryForValue(), name, this.loadDefault(name, currentValue), comment);
        if (StringUtils.isNoneBlank(langKey)) prop.setLanguageKey(langKey);
        if (this.writeProp) prop.set(currentValue);
        return prop;
    }

    public Property getProp(String name, int currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment, this.getCurrentSubLangKey(name));
    }

    public Property getProp(String name, int currentValue) {
        return this.getProp(name, currentValue, null);
    }

    public int get(String name, int currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment).getInt();
    }

    public int get(String name, int currentValue) {
        return this.get(name, currentValue, null);
    }

    // double

    /**
     * @param langKey full langKey, unaffected by the current pushed path.
     */
    public Property getProp(String name, double currentValue, @Nullable String comment, @Nullable String langKey) {
        Property prop = this.config.get(this.getCurrentCategoryForValue(), name, this.loadDefault(name, currentValue), comment);
        if (StringUtils.isNoneBlank(langKey)) prop.setLanguageKey(langKey);
        if (this.writeProp) prop.set(currentValue);
        return prop;
    }

    public Property getProp(String name, double currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment, this.getCurrentSubLangKey(name));
    }

    public Property getProp(String name, double currentValue) {
        return this.getProp(name, currentValue, null);
    }

    public double get(String name, double currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment).getDouble();
    }

    public double get(String name, double currentValue) {
        return this.get(name, currentValue, null);
    }

    // String[]

    /**
     * @param langKey full langKey, unaffected by the current pushed path.
     */
    public Property getProp(String name, String[] currentValues, @Nullable String comment, @Nullable String langKey) {
        Property prop = this.config.get(this.getCurrentCategoryForValue(), name, this.loadDefault(name, currentValues), comment);
        if (StringUtils.isNoneBlank(langKey)) prop.setLanguageKey(langKey);
        if (this.writeProp) prop.set(currentValues);
        return prop;
    }

    public Property getProp(String name, String[] currentValues, @Nullable String comment) {
        return this.getProp(name, currentValues, comment, this.getCurrentSubLangKey(name));
    }

    public Property getProp(String name, String[] currentValues) {
        return this.getProp(name, currentValues, null);
    }

    public String[] get(String name, String[] currentValues, @Nullable String comment) {
        return this.getProp(name, currentValues, comment).getStringList();
    }

    public String[] get(String name, String[] currentValues) {
        return this.get(name, currentValues, null);
    }

    // Enum

    /**
     * @param langKey full langKey, unaffected by the current pushed path.
     */
    private <E extends Enum<E>> Property getProp(String name, E currentValue, @Nullable String comment, @Nullable String langKey) {
        Property prop = this.getProp(name, currentValue.name(), comment, langKey);
        Class<E> enumClass = currentValue.getDeclaringClass();
        E[] enums = enumClass.getEnumConstants();

        String[] names = new String[enums.length];
        String[] langKeys = null;
        boolean isTranslatable = ConfigEntryUtil.IEnumTranslatable.class.isAssignableFrom(enumClass);
        for (E e : enums) {
            names[e.ordinal()] = e.name();
            // Customize langKey
            if (langKey != null) {
                if (langKeys == null) langKeys = new String[enums.length];
                langKeys[e.ordinal()] = isTranslatable ?
                        langKey + ".type." + ((ConfigEntryUtil.IEnumTranslatable) e).getLangKey() :
                        langKey + ".type." + this.formatLangKey(names[e.ordinal()]);
            }
        }
        prop.setValidValues(names);
        if (langKeys != null) prop.setValidValuesDisplay(langKeys);
        ConfigEntryUtil.validifyAllowedValues(prop); // In case Enum.valueOf()
        return prop;
    }

    public <E extends Enum<E>> Property getProp(String name, E currentValue, @Nullable String comment) {
        return this.getProp(name, currentValue, comment, this.getCurrentSubLangKey(name));
    }

    public <E extends Enum<E>> Property getProp(String name, E currentValue) {
        return this.getProp(name, currentValue, null);
    }

    public <E extends Enum<E>> E get(String name, E currentValue, @Nullable String comment) {
        return Enum.valueOf(currentValue.getDeclaringClass(), this.getProp(name, currentValue, comment).getString());
    }

    public <E extends Enum<E>> E get(String name, E currentValue) {
        return this.get(name, currentValue, null);
    }
}
