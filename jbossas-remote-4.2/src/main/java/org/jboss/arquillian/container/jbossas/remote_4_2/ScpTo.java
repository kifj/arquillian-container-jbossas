package org.jboss.arquillian.container.jbossas.remote_4_2;

import com.jcraft.jsch.*;

import java.io.*;

import org.jboss.logging.Logger;

public class ScpTo {
    private static Logger LOG = Logger.getLogger(ScpTo.class);

    public void copy(File lFile, String remoteLocation) throws IOException, JSchException {
        LOG.debug("copy(" + lFile + ", " + remoteLocation + ")");
        FileInputStream fis = null;
        try {
            String user = remoteLocation.substring(0, remoteLocation.indexOf('@'));
            remoteLocation = remoteLocation.substring(remoteLocation.indexOf('@') + 1);
            String host = remoteLocation.substring(0, remoteLocation.indexOf(':'));
            String rfile = remoteLocation.substring(remoteLocation.indexOf(':') + 1);

            JSch jsch = new JSch();
            JSch.setConfig("PreferredAuthentications", "publickey");
            JSch.setConfig("StrictHostKeyChecking", "no");
            jsch.addIdentity(System.getProperty("ssh.identity", System.getProperty("user.home") + "/.ssh/id_rsa"),
                    System.getProperty("ssh.passphrase"));
            jsch.setKnownHosts(System.getProperty("ssh.knownHosts", System.getProperty("user.home")
                    + "/.ssh/known_hosts"));
            Session session = jsch.getSession(user, host, 22);
            session.connect();

            boolean ptimestamp = false;

            // exec 'scp -t rfile' remotely
            String command = "scp " + (ptimestamp ? "-p" : "") + " -t " + rfile;
            Channel channel = session.openChannel("exec");
            ((ChannelExec) channel).setCommand(command);

            // get I/O streams for remote scp
            OutputStream out = channel.getOutputStream();
            InputStream in = channel.getInputStream();

            channel.connect();

            if (checkAck(in) != 0) {
                throw new IOException();
            }

            if (ptimestamp) {
                command = "T" + (lFile.lastModified() / 1000) + " 0";
                // The access time should be sent here,
                // but it is not accessible with JavaAPI ;-<
                command += (" " + (lFile.lastModified() / 1000) + " 0\n");
                out.write(command.getBytes());
                out.flush();
                if (checkAck(in) != 0) {
                    throw new IOException();
                }
            }

            // send "C0644 filesize filename", where filename should not include '/'
            long filesize = lFile.length();
            command = "C0644 " + filesize + " ";
            String lfile = lFile.getAbsolutePath();
            if (lfile.lastIndexOf('/') > 0) {
                command += lfile.substring(lfile.lastIndexOf('/') + 1);
            } else {
                command += lfile;
            }
            command += "\n";
            out.write(command.getBytes());
            out.flush();
            if (checkAck(in) != 0) {
                throw new IOException();
            }

            // send a content of lfile
            fis = new FileInputStream(lfile);
            byte[] buf = new byte[1024];
            while (true) {
                int len = fis.read(buf, 0, buf.length);
                if (len <= 0) {
                    break;
                }
                out.write(buf, 0, len); // out.flush();
            }
            fis.close();
            fis = null;
            // send '\0'
            buf[0] = 0;
            out.write(buf, 0, 1);
            out.flush();
            if (checkAck(in) != 0) {
                throw new IOException();
            }
            out.close();

            channel.disconnect();
            session.disconnect();
        } finally {
            try {
                if (fis != null) {
                    fis.close();
                }
            } catch (Exception ee) {
                LOG.warn(null, ee);
            }
        }
    }

    private int checkAck(InputStream in) throws IOException {
        int b = in.read();
        // b may be 0 for success,
        // 1 for error,
        // 2 for fatal error,
        // -1
        if (b == 0) {
            return b;
        }
        if (b == -1) {
            return b;
        }

        if (b == 1 || b == 2) {
            StringBuffer sb = new StringBuffer();
            int c;
            do {
                c = in.read();
                sb.append((char) c);
            } while (c != '\n');
            if (b == 1) { // error
                LOG.warn(sb.toString());
            }
            if (b == 2) { // fatal error
                LOG.error(sb.toString());
            }
        }
        return b;
    }
}