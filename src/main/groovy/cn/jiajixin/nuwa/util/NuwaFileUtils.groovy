package cn.jiajixin.nuwa.util

import org.apache.commons.io.FileUtils
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Nullable
import org.gradle.api.Project

/**
 * Created by jixin.jia on 15/11/10.
 */
class NuwaFileUtils {

    public static File touchFile(File dir, String path) {
        def file = new File("${dir}/${path}")
        file.getParentFile().mkdirs()
        return file
    }

    public static copyBytesToFile(byte[] bytes, File file) {
        if (!file.exists()) {
            file.createNewFile()
        }
        FileUtils.writeByteArrayToFile(file, bytes)
    }

    /**
     * 从project对象中获得相应属性，并获得其表示路径的File
     * @param project
     * @param property
     * @return
     */
    public static @Nullable File getFileFromProperty(Project project, String property) {
        def file
        if (project.hasProperty(property)) {
            file = new File(project.getProperties()[property])
            if (!file.exists()) {
                throw new InvalidUserDataException("${project.getProperties()[property]} does not exist")
            }
            if (!file.isDirectory()) {
                throw new InvalidUserDataException("${project.getProperties()[property]} is not directory")
            }
        }
        return file
    }

    public static File getVariantFile(File dir, def variant, String fileName) {
        return new File("${dir}/${variant.dirName}/${fileName}")
    }

}
