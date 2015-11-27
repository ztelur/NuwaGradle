package cn.jiajixin.nuwa

import cn.jiajixin.nuwa.util.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project

class NuwaPlugin implements Plugin<Project> {
    HashSet<String> includePackage
    HashSet<String> excludeClass
    def debugOn
    def patchList = []
    def beforeDexTasks = []
    private static final String NUWA_DIR = "NuwaDir"
    private static final String NUWA_PATCHES = "nuwaPatches"

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"

    private static final String DEBUG = "debug"


    @Override
    void apply(Project project) {

        project.extensions.create("nuwa", NuwaExtension, project)



        project.afterEvaluate {
            def extension = project.extensions.findByName("nuwa") as NuwaExtension
            //获得加入和不加入的list,可能为空
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass
            debugOn = extension.debugOn
            //对所有的android的build类型进行操作
            project.android.applicationVariants.each { variant ->

                if (!variant.name.contains(DEBUG) || (variant.name.contains(DEBUG) && debugOn)) {

                    Map hashMap
                    File nuwaDir
                    File patchDir
                    // preDexRelease
                    def preDexTask = project.tasks.findByName("preDex${variant.name.capitalize()}")
                    // dexRelease
                    def dexTask = project.tasks.findByName("dex${variant.name.capitalize()}")
                    // proguardRelease
                    def proguardTask = project.tasks.findByName("proguard${variant.name.capitalize()}")
                    // processReleaseManifest
                    def processManifestTask = project.tasks.findByName("process${variant.name.capitalize()}Manifest")
                    //获得处理完的manifestFile来获得一些应用的基本信息
                    def manifestFile = processManifestTask.outputs.files.files[0]
                    //可以通过命令行的-P参数来指定啦
                    def oldNuwaDir = NuwaFileUtils.getFileFromProperty(project, NUWA_DIR)
                    if (oldNuwaDir) { //可能为空
                        def mappingFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, MAPPING_TXT)
                        NuwaAndroidUtils.applymapping(proguardTask, mappingFile)
                    }
                    if (oldNuwaDir) {
                        def hashFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, HASH_TXT)
                        hashMap = NuwaMapUtils.parseMap(hashFile)
                    }

                    def dirName = variant.dirName
                    nuwaDir = new File("${project.buildDir}/outputs/nuwa")
                    def outputDir = new File("${nuwaDir}/${dirName}")
                    //新建立一个
                    def hashFile = new File(outputDir, "hash.txt")

                    Closure nuwaPrepareClosure = {
                        // 从manifest中读取Application，因为这个文件不能进行添加hack
                        //还有一个不要添加hack的原因是，可能会影响到性能问题啊
                        def applicationName = NuwaAndroidUtils.getApplication(manifestFile)
                        if (applicationName != null) {
                            excludeClass.add(applicationName)
                        }
                        //建立一些必须的文件和文件夹
                        outputDir.mkdirs()
                        if (!hashFile.exists()) {
                            hashFile.createNewFile()
                        }

                        if (oldNuwaDir) {
                            // outputs/nuwa/qihoo/debug/path
                            patchDir = new File("${nuwaDir}/${dirName}/patch")
                            patchDir.mkdirs()
                            patchList.add(patchDir)
                        }
                    }
                    // 生成patch.jar的task,就是打包那个文件下的class到dex中去
                    def nuwaPatch = "nuwa${variant.name.capitalize()}Patch"
                    project.task(nuwaPatch) << {
                        if (patchDir) {
                            NuwaAndroidUtils.dex(project, patchDir)
                        }
                    }
                    def nuwaPatchTask = project.tasks[nuwaPatch]
                    // proguardTask的相关task
                    Closure copyMappingClosure = {
                        if (proguardTask) {
                            //拷贝map.txt
                            def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                            def newMapFile = new File("${nuwaDir}/${variant.dirName}/mapping.txt");
                            FileUtils.copyFile(mapFile, newMapFile)
                        }
                    }

                    if (preDexTask) {
                        //Build Type + Product Flavor = Build Variant
                        def nuwaJarBeforePreDex = "nuwaJarBeforePreDex${variant.name.capitalize()}"
                        project.task(nuwaJarBeforePreDex) << {
                            //获得preDexTask的inputs的file
                            Set<File> inputFiles = preDexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (NuwaProcessor.shouldProcessPreDexJar(path)) {
                                    NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def nuwaJarBeforePreDexTask = project.tasks[nuwaJarBeforePreDex]
                        //插在preDexTask前边
                        nuwaJarBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                        preDexTask.dependsOn nuwaJarBeforePreDexTask

                        nuwaJarBeforePreDexTask.doFirst(nuwaPrepareClosure)

                        def nuwaClassBeforeDex = "nuwaClassBeforeDex${variant.name.capitalize()}"
                        project.task(nuwaClassBeforeDex) << {
                            //处理preDexRelease所有输入的文件
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    // return true if include actually or includePackage is null or empty
                                    //TODO:这里不太好
                                    if (NuwaSetUtils.isIncluded(path, includePackage)) {
                                        //判断是否在excluded
                                        if (!NuwaSetUtils.isExcluded(path, excludeClass)) {
                                            //这是关键部分，要防止打上CLASS_ISPREVERIFIED
                                            def bytes = NuwaProcessor.processClass(inputFile)
                                            path = path.split("${dirName}/")[1]
                                            def hash = DigestUtils.shaHex(bytes)
                                            hashFile.append(NuwaMapUtils.format(path, hash))
                                            //根据hashmap中的值是否相等啊,hashMap是传入的参数，上一次的
                                            if (NuwaMapUtils.notSame(hashMap, path, hash)) {
                                                //就是把class文件复制到patchDir下中
                                                NuwaFileUtils.copyBytesToFile(inputFile.bytes, NuwaFileUtils.touchFile(patchDir, path))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        def nuwaClassBeforeDexTask = project.tasks[nuwaClassBeforeDex]

                        //添加以来
                        nuwaClassBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn nuwaClassBeforeDexTask

                        nuwaClassBeforeDexTask.doLast(copyMappingClosure)

                        nuwaPatchTask.dependsOn nuwaClassBeforeDexTask
                        beforeDexTasks.add(nuwaClassBeforeDexTask)
                    } else { //如果没有preDexRelease
                        def nuwaJarBeforeDex = "nuwaJarBeforeDex${variant.name.capitalize()}"
                        project.task(nuwaJarBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".jar")) {
                                    NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def nuwaJarBeforeDexTask = project.tasks[nuwaJarBeforeDex]
                        nuwaJarBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn nuwaJarBeforeDexTask

                        nuwaJarBeforeDexTask.doFirst(nuwaPrepareClosure)
                        nuwaJarBeforeDexTask.doLast(copyMappingClosure)

                        nuwaPatchTask.dependsOn nuwaJarBeforeDexTask
                        beforeDexTasks.add(nuwaJarBeforeDexTask)
                    }

                }
            }

            project.task(NUWA_PATCHES) << {
                patchList.each { patchDir ->
                    NuwaAndroidUtils.dex(project, patchDir)
                }
            }
            //NUWA_PATCHES需要以来beforeDexTasks
            beforeDexTasks.each {
                project.tasks[NUWA_PATCHES].dependsOn it
            }
        }
    }
}


