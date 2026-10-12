const { withProjectBuildGradle } = require('@expo/config-plugins');
module.exports = config => {
  config = withProjectBuildGradle(config, mod => {
    const dependency = "classpath('com.chaquo.python:gradle:17.0.0')";
    if (!mod.modResults.contents.includes(dependency)) {
      mod.modResults.contents = mod.modResults.contents.replace("classpath('com.android.tools.build:gradle')", "classpath('com.android.tools.build:gradle')\n    " + dependency);
    }
    return mod;
  });
  return config;
};
