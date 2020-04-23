package com.wyl.noquickclick;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.utils.FileUtils;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * author : wangyuelin
 * time   : 2020/4/22 5:49 PM
 * desc   : 封装了通用的逻辑，可以通过集成定制，主要逻辑：jar和class文件的处理流程
 */
public abstract class BaseTransform extends Transform {
    @Override
    public String getName() {
        return "BaseTransform";
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        //编译后的字节码文件，可能是jar里面的也可能是文件夹里面的
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        //所有的module+第三方库
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @Override
    public boolean isIncremental() {
        //支持增量编译
        return true;
    }

    @Override
    public void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        super.transform(transformInvocation);
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        //不支持增量编译，将之前的输出产物，全部删除，避免出现错乱
        if (!transformInvocation.isIncremental()) {
            outputProvider.deleteAll();
        }
        //遍历输入，然后处理，最后保存处理结果
        for (TransformInput input : transformInvocation.getInputs()) {
            //处理jar包里面的class
            for (JarInput jarInput : input.getJarInputs()) {
                handleJarInput(jarInput, transformInvocation);
            }
            //处理文件夹下的class
            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                handleDirectoryInput(directoryInput, transformInvocation);
            }

        }
    }

    /**
     * 处理Directory类型的输入
     * @param directoryInput
     * @param transformInvocation
     */
    private void handleDirectoryInput(DirectoryInput directoryInput, TransformInvocation transformInvocation) {
        if (directoryInput == null || transformInvocation == null) {
            return;
        }
        File inputDir = directoryInput.getFile();
        //查询对应的输入位置
        File outputDir = transformInvocation.getOutputProvider().getContentLocation(directoryInput.getName(), directoryInput.getContentTypes(), directoryInput.getScopes(), Format.DIRECTORY);
        if (transformInvocation.isIncremental()) {
            //增量方式处理
            directoryInput.getChangedFiles().forEach(new BiConsumer<File, Status>() {
                @Override
                public void accept(File inputFile, Status status) {
                    File out = toOutputFile(outputDir, inputDir, inputFile);
                    switch (status) {
                        case NOTCHANGED:
                            break;
                        case CHANGED:
                        case ADDED:
                            if(!inputFile.isDirectory() && !classFilter(inputFile.getName())) {
                                transformFile(inputFile, out, inject());
                            }
                            break;
                        case REMOVED:
                            try {
                                FileUtils.deleteIfExists(out);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            break;
                    }

                }
            });
        } else {
            for (File in : FileUtils.getAllFiles(inputDir)) {
                File out = toOutputFile(outputDir, inputDir, in);
                if(!classFilter(in.getName())) {
                    transformFile(in, out, inject());
                }
            }
        }
    }

    /**
     * 对输入的class文件处理
     * @param inputFile
     * @param outputFile
     * @param inject
     */
    private void transformFile(File inputFile, File outputFile, BiConsumer<InputStream, OutputStream> inject) {
        if(inputFile == null || outputFile == null || inject == null) {
            return;
        }
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            Files.createParentDirs(outputFile);
            fis = new FileInputStream(inputFile);
            fos = new FileOutputStream(outputFile);
            inject.accept(fis, fos);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if(fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }


    /**
     * 据输入的文件相应的输出文件
     * @param outputDir
     * @param inputDir
     * @param inputFile
     * @return
     */
    private File toOutputFile(File outputDir, File inputDir, File inputFile) {
        return new File(
                outputDir,
                FileUtils.relativePossiblyNonExistingPath(inputFile, inputDir)
        );
    }

    /**
     * 处理jar类型的输入
     *
     * @param jarInput
     * @param transformInvocation
     */
    private void handleJarInput(JarInput jarInput, TransformInvocation transformInvocation) {
        if (jarInput == null || transformInvocation == null) {
            return;
        }
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        File jarInputFile = jarInput.getFile();
        //查询得到输入对应的输出路径
        File jarOutPutFile = outputProvider.getContentLocation(jarInput.getName(), jarInput.getContentTypes(), jarInput.getScopes(), Format.JAR);
        if (transformInvocation.isIncremental()) {
            //增量处理jar包
            switch (jarInput.getStatus()) {
                case ADDED:
                case CHANGED:
                    //新增或者修改的jar需要处理
                    transformJar(jarInputFile, jarOutPutFile, inject());
                    break;
                case REMOVED:
                    //删除输出jar文件
                    try {
                        FileUtils.delete(jarOutPutFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                case NOTCHANGED:
                    //没有改变，不需要处理
                    break;
            }
        } else {
            //不是增量编译，直接对处理
            transformJar(jarInputFile, jarOutPutFile, inject());
        }
    }

    /**
     * 对输入的jar文件处理，然后保存到输出jar文件
     *
     * @param inputJarFile
     * @param outputJarFile
     * @param inject
     */
    private void transformJar(File inputJarFile, File outputJarFile, BiConsumer<InputStream, OutputStream> inject) {
        if (inputJarFile == null || outputJarFile == null || inject == null) {
            return;
        }
        //确保输出目录存在
        try {
            Files.createParentDirs(outputJarFile);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        ZipInputStream zis = null;
        ZipOutputStream zos = null;
        try {
            zis = new ZipInputStream(new FileInputStream(inputJarFile));
            zos = new ZipOutputStream(new FileOutputStream(outputJarFile));
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null && isValidZipEntryName(zipEntry)) {
                if (!zipEntry.isDirectory() && !classFilter(zipEntry.getName())) {
                    zos.putNextEntry(new ZipEntry(zipEntry.getName()));
                    inject.accept(zis, zos);
                }
                zipEntry = zis.getNextEntry();
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if(zis != null) {
                try {
                    zis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if(zos != null) {
                try {
                    zos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 验证名称是否有效
     *
     * @param zipEntry
     * @return
     */
    private boolean isValidZipEntryName(ZipEntry zipEntry) {
        return !zipEntry.getName().contains("../");
    }

    /**
     * class过滤，子类覆写实现自己的过滤
     *
     * @param name
     * @return true：表示需要过滤；false：表示不需要过滤
     */
    protected boolean classFilter(String name) {
        return false;
    }

    /**
     * 子类覆写，实现代码的注入逻辑
     *
     * @return
     */
    protected abstract BiConsumer<InputStream, OutputStream> inject();
}