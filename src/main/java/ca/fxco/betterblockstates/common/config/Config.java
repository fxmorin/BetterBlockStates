package ca.fxco.betterblockstates.common.config;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.CustomValue.CvType;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@SuppressWarnings("CanBeFinal")
public class Config {
    private static final Logger LOGGER = LogManager.getLogger("Config");

    private static final String JSON_KEY_BLOCKSTATE_OPTIONS = "betterblockstates:options";

    private final Map<String, Option> options = new HashMap<>();
    private final Set<Option> optionsWithDependencies = new ObjectLinkedOpenHashSet<>();

    private Config() {
        // Defines the default rules which can be configured by the user or other mods.
        // You must manually add a rule for any new mixins not covered by an existing package rule.

        this.addMixinRule("blockstate_tickers", false);
        this.addMixinRule("blockstate_listeners", false);
        this.addMixinRule("skippable_block_entity", false);

        this.addMixinRule("blocks.daylight_detector", false); //Not working yet
        this.addMixinRule("blocks.comparator", true);
        this.addMixinRule("blocks.sculk_sensor", true);
        this.addMixinRule("blocks.sculk_sensor.movable", false);


        this.addRuleDependency("blocks.daylight_detector", "blockstate_tickers", true);
        this.addRuleDependency("blocks.comparator", "skippable_block_entity", true);
        this.addRuleDependency("blocks.sculk_sensor", "skippable_block_entity", true);
        this.addRuleDependency("blocks.sculk_sensor", "blockstate_listeners", true);
    }

    /**
     * Loads the configuration file from the specified location. If it does not exist, a new configuration file will be
     * created. The file on disk will then be updated to include any new options.
     */
    public static Config load(File file) {
        Config config = new Config();

        if (file.exists()) {
            Properties props = new Properties();

            try (FileInputStream fin = new FileInputStream(file)) {
                props.load(fin);
            } catch (IOException e) {
                throw new RuntimeException("Could not load config file", e);
            }

            config.readProperties(props);
        } else {
            try {
                writeDefaultConfig(file);
            } catch (IOException e) {
                LOGGER.warn("Could not write default configuration file", e);
            }
        }

        config.applyModOverrides();

        // Check dependencies several times, because one iteration may disable a rule required by another rule
        // This terminates because each additional iteration will disable one or more rules, and there is only a finite number of rules
        while (config.applyDependencies()) {
            ;
        }

        return config;
    }

    /**
     * Defines a dependency between two registered mixin rules. If a dependency is not satisfied, the mixin will
     * be disabled.
     *
     * @param rule          the mixin rule that requires another rule to be set to a given value
     * @param dependency    the mixin rule the given rule depends on
     * @param requiredValue the required value of the dependency
     */
    @SuppressWarnings("SameParameterValue")
    private void addRuleDependency(String rule, String dependency, boolean requiredValue) {
        String ruleOptionName = getMixinRuleName(rule);
        Option option = this.options.get(ruleOptionName);
        if (option == null) {
            LOGGER.error("Option {} for dependency '{} depends on {}={}' not found. Skipping.", rule, rule, dependency, requiredValue);
            return;
        }
        String dependencyOptionName = getMixinRuleName(dependency);
        Option dependencyOption = this.options.get(dependencyOptionName);
        if (dependencyOption == null) {
            LOGGER.error("Option {} for dependency '{} depends on {}={}' not found. Skipping.", dependency, rule, dependency, requiredValue);
            return;
        }
        option.addDependency(dependencyOption, requiredValue);
        this.optionsWithDependencies.add(option);
    }


    /**
     * Defines a Mixin rule which can be configured by users and other mods.
     *
     * @param mixin   The name of the mixin package which will be controlled by this rule
     * @param enabled True if the rule will be enabled by default, otherwise false
     * @throws IllegalStateException If a rule with that name already exists
     */
    private void addMixinRule(String mixin, boolean enabled) {
        String name = getMixinRuleName(mixin);

        if (this.options.putIfAbsent(name, new Option(name, enabled, false)) != null) {
            throw new IllegalStateException("Mixin rule already defined: " + mixin);
        }
    }

    private void readProperties(Properties props) {
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();

            Option option = this.options.get(key);

            if (option == null) {
                LOGGER.warn("No configuration key exists with name '{}', ignoring", key);
                continue;
            }

            boolean enabled;

            if (value.equalsIgnoreCase("true")) {
                enabled = true;
            } else if (value.equalsIgnoreCase("false")) {
                enabled = false;
            } else {
                LOGGER.warn("Invalid value '{}' encountered for configuration key '{}', ignoring", value, key);
                continue;
            }

            option.setEnabled(enabled, true);
        }
    }

    private void applyModOverrides() {
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            ModMetadata meta = container.getMetadata();

            if (meta.containsCustomValue(JSON_KEY_BLOCKSTATE_OPTIONS)) {
                CustomValue overrides = meta.getCustomValue(JSON_KEY_BLOCKSTATE_OPTIONS);

                if (overrides.getType() != CvType.OBJECT) {
                    LOGGER.warn("Mod '{}' contains invalid BetterBlockStates option overrides, ignoring", meta.getId());
                    continue;
                }

                for (Map.Entry<String, CustomValue> entry : overrides.getAsObject()) {
                    this.applyModOverride(meta, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void applyModOverride(ModMetadata meta, String name, CustomValue value) {
        Option option = this.options.get(name);

        if (option == null) {
            LOGGER.warn("Mod '{}' attempted to override option '{}', which doesn't exist, ignoring", meta.getId(), name);
            return;
        }

        if (value.getType() != CvType.BOOLEAN) {
            LOGGER.warn("Mod '{}' attempted to override option '{}' with an invalid value, ignoring", meta.getId(), name);
            return;
        }

        boolean enabled = value.getAsBoolean();

        // disabling the option takes precedence over enabling
        if (!enabled && option.isEnabled()) {
            option.clearModsDefiningValue();
        }

        if (!enabled || option.isEnabled() || option.getDefiningMods().isEmpty()) {
            option.addModOverride(enabled, meta.getId());
        }
    }

    /**
     * Returns the effective option for the specified class name. This traverses the package path of the given mixin
     * and checks each root for configuration rules. If a configuration rule disables a package, all mixins located in
     * that package and its children will be disabled. The effective option is that of the highest-priority rule, either
     * a enable rule at the end of the chain or a disable rule at the earliest point in the chain.
     *
     * @return Null if no options matched the given mixin name, otherwise the effective option for this Mixin
     */
    public Option getEffectiveOptionForMixin(String mixinClassName) {
        int lastSplit = 0;
        int nextSplit;

        Option rule = null;

        while ((nextSplit = mixinClassName.indexOf('.', lastSplit)) != -1) {
            String key = getMixinRuleName(mixinClassName.substring(0, nextSplit));

            Option candidate = this.options.get(key);

            if (candidate != null) {
                rule = candidate;

                if (!rule.isEnabled()) {
                    return rule;
                }
            }

            lastSplit = nextSplit + 1;
        }

        return rule;
    }

    /**
     * Tests all dependencies and disables options when their dependencies are not met.
     */
    private boolean applyDependencies() {
        boolean changed = false;
        for (Option optionWithDependency : this.optionsWithDependencies) {
            changed |= optionWithDependency.disableIfDependenciesNotMet(LOGGER);
        }
        return changed;
    }

    private static void writeDefaultConfig(File file) throws IOException {
        File dir = file.getParentFile();

        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw new IOException("Could not create parent directories");
            }
        } else if (!dir.isDirectory()) {
            throw new IOException("The parent file is not a directory");
        }

        try (Writer writer = new FileWriter(file)) {
            writer.write("# This is the configuration file for BetterBlockStates.\n");
            writer.write("# This file exists for debugging purposes and should not be configured otherwise.\n");
            writer.write("#\n");
            writer.write("# By default, this file will be empty except for this notice.\n");
        }
    }

    private static String getMixinRuleName(String name) {
        return "mixin." + name;
    }

    public int getOptionCount() {
        return this.options.size();
    }

    public int getOptionOverrideCount() {
        return (int) this.options.values()
                .stream()
                .filter(Option::isOverridden)
                .count();
    }
}