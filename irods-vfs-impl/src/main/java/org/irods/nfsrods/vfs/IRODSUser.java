package org.irods.nfsrods.vfs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.irods.jargon.core.connection.IRODSAccount;
import org.irods.jargon.core.exception.DataNotFoundException;
import org.irods.jargon.core.exception.JargonException;
import org.irods.jargon.core.pub.IRODSAccessObjectFactory;
import org.irods.jargon.core.pub.domain.User;
import org.irods.jargon.core.pub.io.IRODSFile;
import org.irods.nfsrods.config.IRODSProxyAdminAccountConfig;
import org.irods.nfsrods.config.IRODSServerConfig;
import org.irods.nfsrods.config.NFSServerConfig;
import org.irods.nfsrods.config.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IRODSUser
{
    private static final Logger log_ = LoggerFactory.getLogger(IRODSIdMap.class);

    private Map<Long, Path> inodeToPath_;
    private Map<Path, Long> pathToInode_;
    private Set<Long> availableInodeNumbers_;
    private AtomicLong fileID_;
    private IRODSAccessObjectFactory factory_;
    private IRODSAccount proxiedAcct_;
    private IRODSFile rootFile_;
    private int userID_;

    public IRODSUser(String _username, ServerConfig _config, IRODSAccessObjectFactory _factory)
    {
        inodeToPath_ = new NonBlockingHashMap<>();
        pathToInode_ = new NonBlockingHashMap<>();
        availableInodeNumbers_ = Collections.synchronizedSet(new HashSet<>());
        fileID_ = new AtomicLong(1); // Inode numbers start at 1

        NFSServerConfig nfsSvrConfig = _config.getNfsServerConfig();
        IRODSProxyAdminAccountConfig proxyConfig = _config.getIRODSProxyAdminAcctConfig();
        IRODSServerConfig rodsSvrConfig = _config.getIRODSServerConfig();

        String adminAcct = proxyConfig.getUsername();
        String adminPw = proxyConfig.getPassword();
        String zone = rodsSvrConfig.getZone();

        String rootPath = Paths.get(nfsSvrConfig.getIRODSMountPoint()).toString();
        log_.debug("IRODSUser :: iRODS mount point = {}", rootPath);
        log_.debug("IRODSUser :: Creating proxy for username [{}] ...", _username);

        try
        {
            proxiedAcct_ = IRODSAccount.instanceWithProxy(rodsSvrConfig.getHost(), rodsSvrConfig.getPort(), _username,
                                                          adminPw, rootPath, zone, rodsSvrConfig.getDefaultResource(),
                                                          adminAcct, zone);
            factory_ = _factory;
            rootFile_ = factory_.getIRODSFileFactory(proxiedAcct_).instanceIRODSFile(rootPath);

            User user = factory_.getUserAO(proxiedAcct_).findByName(_username);
            userID_ = Integer.parseInt(user.getId());

            establishRoot();
        }
        catch (JargonException e)
        {
            log_.error(e.getMessage());
        }
        finally
        {
            factory_.closeSessionAndEatExceptions();
        }
    }

    public int getUserID()
    {
        return this.userID_;
    }

    public String getAbsolutePath()
    {
        return rootFile_.getAbsolutePath();
    }

    public Map<Long, Path> getInodeToPathMap()
    {
        return inodeToPath_;
    }

    public Map<Path, Long> getPathToInodeMap()
    {
        return pathToInode_;
    }

    public Long getAndIncrementFileID()
    {
        if (!availableInodeNumbers_.isEmpty())
        {
            Iterator<Long> it = availableInodeNumbers_.iterator();
            Long inodeNumber = it.next();
            it.remove();
            return inodeNumber;
        }

        return fileID_.getAndIncrement();
    }

    public IRODSAccessObjectFactory getIRODSAccessObjectFactory()
    {
        return factory_;
    }

    public IRODSAccount getAccount()
    {
        return proxiedAcct_;
    }

    public IRODSFile getRoot()
    {
        return rootFile_;
    }

    private void establishRoot()
    {
        if (!rootFile_.exists())
        {
            log_.error("Root file does not exist or it cannot be read");

            try
            {
                throw new DataNotFoundException("Cannot establish root at [" + rootFile_ + "]");
            }
            catch (DataNotFoundException e)
            {
                log_.error(e.getMessage());
            }

            return;
        }

        log_.debug("establishRoot :: Mapping root to [{}] ...", rootFile_);

        map(getAndIncrementFileID(), rootFile_.getAbsolutePath());

        log_.debug("establishRoot :: Mapping successful.");
    }

    public void map(Long _inodeNumber, String _path)
    {
        map(_inodeNumber, Paths.get(_path));
    }

    public void map(Long _inodeNumber, Path _path)
    {
        log_.debug("map :: mapping inode number to path [{} => {}] ...", _inodeNumber, _path);

        Path otherPath = inodeToPath_.putIfAbsent(_inodeNumber, _path);

        log_.debug("map :: previously mapped path [{}]", otherPath);

        if (otherPath != null)
        {
            throw new IllegalStateException("Inode number is already mapped to exisiting path");
        }

        Long otherInodeNumber = pathToInode_.putIfAbsent(_path, _inodeNumber);

        if (otherInodeNumber != null)
        {
            if (inodeToPath_.remove(_inodeNumber) != _path)
            {
                throw new IllegalStateException("Failed to rollback mapping");
            }

            throw new IllegalStateException("Path is already mapped to exisiting inode number");
        }
    }

    public void unmap(Long _inodeNumber, Path _path)
    {
        final boolean storeInAvailableInodeNumbersSet = true;
        unmap(_inodeNumber, _path, storeInAvailableInodeNumbersSet);
    }

    private void unmap(Long _inodeNumber, Path _path, boolean _storeInAvailableInodeNumbersSet)
    {
        log_.debug("unmap :: unmapping inode number and path [{} => {}] ...", _inodeNumber, _path);

        if (!_path.equals(inodeToPath_.remove(_inodeNumber)))
        {
            throw new IllegalStateException("Invalid mapping");
        }

        if (pathToInode_.remove(_path) != _inodeNumber)
        {
            throw new IllegalStateException("Invalid mapping");
        }

        if (_storeInAvailableInodeNumbersSet)
        {
            availableInodeNumbers_.add(_inodeNumber);
        }
    }

    public void remap(Long _inodeNumber, Path _oldPath, Path _newPath)
    {
        final boolean storeInAvailableInodeNumbersSet = false;
        unmap(_inodeNumber, _oldPath, storeInAvailableInodeNumbersSet);
        map(_inodeNumber, _newPath);
    }

    @Override
    public String toString()
    {
        return "IRODSUser{proxiedAccount=" + proxiedAcct_ + ", userID=" + userID_ + '}';
    }
}
