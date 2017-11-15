package edu.groupfour.model;

import com.sun.javaws.exceptions.InvalidArgumentException;

import java.io.*;
import java.nio.file.*;
import java.rmi.NoSuchObjectException;
import java.util.ArrayList;
import java.util.HashMap;

public class Locker implements Serializable {
    // haa! PUNS!
    transient private final String chunkMapSerName = "ChunkMap.ser";
    transient private final String rabinSerName = "FingerPrinter.ser";

    private String path; //path to locker
    private ArrayList<LockerFile> files; //paths of the files that are to be added to the locker
    private HashMap<FileChunkHash, FileChunk> chunkMap;
    private RabinFingerPrint rabin;
    transient private boolean isMutated; // true if the chunk map was changed since loading the last state

    /**
     * Loads or creates a new locker at the specified path.
     * @param path - path to where the locker is located or to be created
     * @param isNewLocker - true if creating a new locker instead of loading it
     * @throws IOException - if any of the files/folders cannot either be loaded or created
     */
    public Locker(String path, boolean isNewLocker) throws IOException {
        this.path = path;
        this.chunkMap = new HashMap<>();
        this.files = new ArrayList<>();
        // rabin will be loaded by the load function since it is locker dependent
        if(!isNewLocker) {
            try {
                this.load();
                this.isMutated = false;
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        } else {
            // default chunk size is 4 kibibytes
            this.init(path, 4096);
            this.isMutated = true;
        }
    }

    /**
     * Loads or creates a new locker at the specified path. If creating, sets the
     * rabin finger print's parameters such that chunk sizes approximates equal to the input size.
     * @param path - path to where the locker is located or to be created
     * @param isNewLocker - true if creating a new locker instead of loading it
     * @param chunkSize - average approximate size of the file chunks in bytes
     * @throws IOException - if any of the files/folders cannot either be loaded or created
     */
    public Locker(String path, boolean isNewLocker, int chunkSize) throws IOException {
        this.path = path;
        this.chunkMap = new HashMap<>();
        this.files = new ArrayList<>();
        // rabin will be loaded by the load function since it is locker dependent
        if(!isNewLocker) {
            try {
                this.load();
                this.isMutated = false;
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        } else {
            this.init(path, chunkSize);
        }
    }


    /**
     * Creates a new locker at the path specified by path.
     * First creates the .locker and .files folders.
     * Then initializes the fingerprinter and saves its parameters to the .locker folder.
     * @param path - path to where the locker folder will be created
     */
    public void init(String path, int chunkSize) {
        // TODO : folder name of the locker could be made changeable for the user to customize
        Path lockerRootPath = Paths.get(path, "Locker");
        if (Files.exists(lockerRootPath, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("Folder " + "Locker" + " already exists at specified path.");
            System.exit(1);
        }

        try {
            Files.createDirectory(lockerRootPath);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // Holds the serialized Locker Object's chunkMap and metadata about the fingerprinter
        Path lockerlockerPath = Paths.get(lockerRootPath.toString(), ".locker");
        try {
            Files.createDirectory(lockerlockerPath);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // holds the serialized LockerFile objects from Locker's ArrayList of LockerFile
        Path lockerFilePath = Paths.get(lockerRootPath.toString(), ".files");
        try {
            Files.createDirectory(lockerFilePath);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        this.rabin = new RabinFingerPrint(chunkSize);

        try {
            this.save();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Adds a single file to the locker.
     * @param filePath - Path to the file to be added to the locker.
     * @throws IOException - if the file to be added cannot be read from disk.
     */
    public void addFile(String filePath) throws IOException {
        this.isMutated = true;
    }

    /**
     * Adds an entire directory's content to the locker.
     * @param dirPath - Path to the directory whose contents are being added to the locker.
     * @param recursiveAdd - If true, adds all subdirectories recursively to the locker.
     * @throws IOException - if files to be added cannot be read from disk.
     */
    public void addDir(String dirPath, boolean recursiveAdd) throws IOException {
        this.isMutated = true;
        File dir  = new File(dirPath);
        if (!dir.isDirectory()) {
            System.out.println("Input path does not point to a directory.");
            System.exit(1);
        }

        File [] dirListing = dir.listFiles();

        if (!recursiveAdd) {
            // only add the contents of this dir, do not recurse into child dirs
            if (dirListing == null) {
                System.out.println("Directory to be added is empty. Exiting.");
                System.exit(1);
            }

            for (File f : dirListing) {
                try {
                    if (f.isFile()) {
                        this.addFile(f.getPath());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }

        } else {
            // recursively add all the contents of dir and all its child dirs
            if (dirListing == null) {
                System.out.println("Directory " + dirPath + " is empty. Nothing added to locker.");
                return;
            }
            for (File f : dirListing) {
                try {
                    if (f.isFile())
                        this.addFile(f.getPath());
                    else if (f.isDirectory())
                        this.addDir(f.getPath(), true);
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }


    /**
     * Reconstructs a file or directory that was added to the locker, and writes to the desired location.
     * @param localFilePath - Local path of the file or directory to be reconstructed, starting at the Locker's root.
     * @param targetPath - Path to which the reconstructed file or folder is to be written.
     * @throws IOException - if the reconstruction cannot be written to disk.
     * @throws InvalidArgumentException - if the input file does not exist in the locker.
     */
    public void retrieve(String localFilePath, String targetPath) throws IOException, InvalidArgumentException {
        this.deferredFileLoader(localFilePath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FileChunk chunk;
        for (FileChunkHash hash : this.files.get(0).chunkHashes) {
            chunk = this.chunkMap.get(hash);
            baos.write(chunk.getPayload());
        }

        try {
            FileOutputStream fos = new FileOutputStream(targetPath);
            baos.writeTo(fos);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }


    /**
     * Implements the serialization to save the locker object to disk.
     * @throws IOException - if the object cannot be saved.
     */
    public void save() throws IOException {
        FileOutputStream fos;
        ObjectOutputStream oos;

        // first save chunkMap
        if (this.isMutated) {
            try {
                Path mapPath = Paths.get(this.path, ".locker" , this.chunkMapSerName);
                fos = new FileOutputStream(mapPath.toFile(), false);
                oos = new ObjectOutputStream(fos);
                oos.writeObject(this.chunkMap);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }

            // fingerprinter
            try {
                Path fingerprintPath = Paths.get(this.path, ".locker", this.rabinSerName);
                fos = new FileOutputStream(fingerprintPath.toString(), false);
                oos = new ObjectOutputStream(fos);
                oos.writeObject(this.rabin);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }

        // now all files if they were mutated
        for (LockerFile f : this.files) {
            if (f.isMutated) {
                try {
                    Path filePath = Paths.get(this.path, ".files", f.localFilePath + ".ser");
                    fos = new FileOutputStream(filePath.toString(), false);
                    oos = new ObjectOutputStream(fos);
                    oos.writeObject(f);
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }

    /**
     * Implements the de-serialization to load the locker object from disk.
     * @throws IOException - if the object cannot be loaded
     */
    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        // set everything's isMutated to flase when loading
        // chunk map and finger printer are reloaded every time
        FileInputStream fis;
        ObjectInputStream ois;

        try {
            Path mapPath = Paths.get(this.path, ".locker" , this.chunkMapSerName);
            fis = new FileInputStream(mapPath.toString());
            ois = new ObjectInputStream(fis);
            // unchecked cast, not sure what else can be done
            this.chunkMap = (HashMap<FileChunkHash, FileChunk>) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        try {
            Path fingerprintPath = Paths.get(this.path, ".locker", this.rabinSerName);
            fis = new FileInputStream(fingerprintPath.toString());
            ois = new ObjectInputStream(fis);
            this.rabin = (RabinFingerPrint) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        // That's it, none of the LockerFiles are actually loaded here.
        // We defer the file reading from disk to actual operations on those files.
        // This is to save on disk IO bottle necks in case of large lockers.
    }

    /**
     * Intended to be called after the class is instantiated, this method only
     * loads in a single file from the disk and deserializes it, either to make modifications to the file,
     * or to overwrite it or to retrieve it. This allows us to not load all the deserialized LockerFile classes
     * every time we load in a locker that already exists. Removed disk IO as a major bottleneck.
     * @param localPath - path to the file to be loaded
     * @throws IOException - if the serialized object cannot be read.
     */
    private void deferredFileLoader(String localPath) throws IOException {
        Path filePath = Paths.get(this.path, localPath + ".ser");
        if (!filePath.toFile().exists())
            throw new NoSuchObjectException("File " + localPath + " not found in locker.");

        FileInputStream fis;
        ObjectInputStream ois;
        try {
            fis = new FileInputStream(filePath.toString());
            ois = new ObjectInputStream(fis);
            LockerFile file = (LockerFile) ois.readObject();
            this.files.add(file);
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
