package com.upload.app.modular.system.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.upload.app.core.redission.DistributedRedisLock;
import com.upload.app.core.util.UnicodeUtil;
import com.upload.app.modular.system.dao.*;
import com.upload.app.modular.system.model.*;
import com.upload.app.modular.system.service.*;
import com.upload.app.core.rpc.Api;
import com.upload.app.core.util.Sha256;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;


@Service
public class DecodeServiceImpl implements DecodeService {

    @Resource
    private CreateMapper createMapper;

    @Resource
    private FchXsvLinkMapper fchXsvLinkMapper;

    @Resource
    private UpdateMapper updateMapper;

    @Resource
    private AddressDriveLinkMapper addressDriveLinkMapper;

    @Resource
    private DriveTxAddressMapper driveTxAddressMapper;

    @Resource
    private DriveUtxoMapper driveUtxoMapper;

    @Resource
    private TokenDecodeService tokenDecodeService;

    @Resource
    private ScriptSlpSendService scriptSlpSendService;

    @Resource
    private ScriptTokenLinkService scriptTokenLinkService;

    @Resource
    private ScriptUtxoTokenLinkService scriptUtxoTokenLinkService;

    @Resource
    private AddressScriptLinkService addressScriptLinkService;

    @Resource
    private ScriptTokenDestructionService scriptTokenDestructionService;

    @Resource
    private BalanceHistoryService balanceHistoryService;

    static String deposeFilesDir = "/java/testUpload/data/";

    @Value("${sys.address}")
    private String sysAddress;

    @Value("${sys.tokenAddress}")
    private String sysTokenAddress;

    @Override
    @Transactional(rollbackFor=Exception.class)
    public String decodeCreate(JSONArray jsonArray, BigDecimal sumFee, String drive, Integer size) throws Exception {

        for (Object ob : jsonArray) {

            String tx = (String)ob;

            Long count = driveTxAddressMapper.findByTxidCount(tx);

            if (count > 0)
                continue;

            try {

                JSONObject txTransaction = Api.GetRawTransaction(tx);
                JSONArray vins = txTransaction.getJSONArray("vin");
                authorityVins(vins);

                JSONArray vouts = txTransaction.getJSONArray("vout");

                StringBuilder data = new StringBuilder();

                JSONObject jb = new JSONObject();
                List<AddressDriveLink> addressDriveList = new ArrayList<>();

                String consValue = "";

                for (Object v : vouts) {

                    JSONObject vout = (JSONObject)v;

                    BigDecimal value = vout.getBigDecimal("value");

                    Integer voutn = vout.getInteger("n");

                    String n = UnicodeUtil.intToHex(voutn);

                    JSONObject scriptPubKey =  vout.getJSONObject("scriptPubKey");

                    String type = scriptPubKey.getString("type");

                    if ("nonstandard".equals(type)) {

                        String content = scriptPubKey.getString("hex");
                        String driveId = Sha256.getSHA256(tx+n);

                        String ifHex = content.substring(0, 2);

                        if (!"63".equals(ifHex)) {
                            continue;
                        }

                        List<String> addressList = new ArrayList<>();
                        StringBuffer script = new StringBuffer();

                        content = content.replaceFirst(ifHex, "");

                        String oph = content.substring(0, 6);

                        if (!"76a914".equals(oph)) {
                            continue;
                        }

                        content = content.replaceFirst(oph, "");

                        String address1 = content.substring(0, 40);
                        addressList.add(address1);

                        FchXsvLink fchXsvLink1 = fchXsvLinkMapper.findByHash(address1);

                        if (fchXsvLink1 != null) {
                            if (!fchXsvLink1.getXsvAddress().equals(sysAddress)) {
                                AddressDriveLink addressDriveLink = new AddressDriveLink();
                                addressDriveLink.setAddress(fchXsvLink1.getAddressHash());
                                addressDriveLink.setDriveId(driveId);
                                addressDriveList.add(addressDriveLink);
                            }
                        }

                        content = content.replaceFirst(address1, "");

                        String opa = content.substring(0, 4);

                        script.append(ifHex).append(oph).append(address1).append(opa);

                        if (!"88ac".equals(opa)) {
                            continue;
                        }

                        content = content.replaceFirst(opa, "");

                        String if67 = content.substring(0, 2);

                        Boolean falg = false;

                        if ("67".equals(if67)) {

                            String fs = list67(content, addressDriveList, driveId, addressList, script);

                            if (fs == null)
                                continue;
                            else {

                                content = fs;

                                while (true) {
                                    fs = list67(content, addressDriveList, driveId, addressList, script);
                                    if (fs == null)
                                        break;
                                    content = fs;
                                    fs = content.substring(0, 2);

                                    if (!"67".equals(fs))
                                        break;
                                }

                                if (content == null)
                                    continue;
                            }
                            falg = true;
                        }

                        String a68 = content.substring(0, 2);

                        if (!"68".equals(a68) && !falg){
                            continue;
                        }

                        content = content.replaceFirst(a68, "");

                        String a6 = content.substring(0, 2);

                        if (!a6.equals("6a"))
                            continue;

                        content = content.replaceFirst(a6, "");

                        String totalLength_Hex = content.substring(0, 2);

                        content = content.replaceFirst(totalLength_Hex, "");

                        if ("4c".equals(totalLength_Hex)) {

                            String length_hex = content.substring(0, 2);
                            Integer length = UnicodeUtil.decodeHEX(length_hex);
                            content = content.replaceFirst(length_hex, "");
                            content = content.substring(0, length*2);


                        } else if ("4d".equals(totalLength_Hex)) {

                            String length_hex = content.substring(0, 4);
                            String b = length_hex.substring(0,2);
                            String a = length_hex.substring(2,4);
                            String c = a+b;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex, "");
                            content = content.substring(0, length*2);

                        } else if ("4e".equals(totalLength_Hex)) {

                            String length_hex = content.substring(0, 8);
                            String b = length_hex.substring(0,4);
                            String b2 = b.substring(0,2);
                            String b1 = b.substring(2,4);
                            String bb = b1 + b2;
                            String a = length_hex.substring(4,8);
                            String a2 = a.substring(0,2);
                            String a1 = a.substring(2,4);
                            String aa = a1+a2;
                            String c = aa+bb;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex, "");
                            content = content.substring(0, length*2);

                        } else {

                            Integer length = UnicodeUtil.decodeHEX(totalLength_Hex);
                            content = content.substring(0, length*2);

                        }

                        String metadata = content;

                        jb.put("driveId", driveId);
                        jb.put("metadata", metadata);
                        jb.put("n", voutn);
                        jb.put("txid", tx);

                        consValue = value.toString();


                    } else if (value.compareTo(new BigDecimal("0")) == 0) {

                        String content = scriptPubKey.getString("hex");
                        content = content.replaceFirst("006a","");
                        String length_hex = content.substring(0,2);
                        content = content.replaceFirst(length_hex,"");

                        if ("4c".equals(length_hex)) {

                            length_hex = content.substring(0,2);
                            Integer length = UnicodeUtil.decodeHEX(length_hex);
                            content = content.replaceFirst(length_hex,"");
                            String contentData = content.substring(0, length * 2);
                            data.append(contentData);
                            jb.put("data", contentData);

                        } else if ("4d".equals(length_hex)) {

                            length_hex = content.substring(0, 4);
                            String b = length_hex.substring(0,2);
                            String a = length_hex.substring(2,4);
                            String c = a+b;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex,"");
                            String contentData = content.substring(0, length * 2);
                            data.append(contentData);
                            jb.put("data", contentData);

                        } else if ("4e".equals(length_hex)) {

                            length_hex = content.substring(0, 8);
                            String b = length_hex.substring(0,4);
                            String b2 = b.substring(0,2);
                            String b1 = b.substring(2,4);
                            String bb = b1 + b2;
                            String a = length_hex.substring(4,8);
                            String a2 = a.substring(0,2);
                            String a1 = a.substring(2,4);
                            String aa = a1+a2;
                            String c = aa+bb;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex, "");
                            String contentData = content.substring(0, length * 2);
                            data.append(contentData);
                            jb.put("data", contentData);

                        } else {
                            Integer length = UnicodeUtil.decodeHEX(length_hex);
                            String contentData = content.substring(0, length*2);
                            data.append(contentData);
                            jb.put("data", contentData);
                        }

                    }

                }

                for (AddressDriveLink addressDrive : addressDriveList) {

                    DriveTxAddress driveTxAddress = new DriveTxAddress();
                    driveTxAddress.setAddress(addressDrive.getAddress());
                    driveTxAddress.setN(jb.getInteger("n"));
                    driveTxAddress.setTxid(jb.getString("txid"));
                    driveTxAddress.setCreateDate(new Date());

                    if (drive != null && !"".equals(drive)) {
                        driveTxAddress.setDriveId(drive);
                        driveTxAddress.setUpdateId(jb.getString("driveId"));
                        addressDrive.setStatus(1);
                    } else {
                        driveTxAddress.setDriveId(jb.getString("driveId"));
                        addressDrive.setStatus(0);
                    }

                    driveTxAddressMapper.insert(driveTxAddress);
                    addressDriveLinkMapper.insert(addressDrive);

                }

                if (drive != null) {

                    DriveUtxo driveUtxo = new DriveUtxo();
                    driveUtxo.setN(jb.getInteger("n"));
                    driveUtxo.setTxid(jb.getString("txid"));
                    driveUtxo.setValue(consValue);
                    driveUtxo.setDriveId(drive);
                    driveUtxoMapper.insert(driveUtxo);

                    Update update = new Update();
                    update.setMetadata(jb.getString("metadata"));
//                    update.setData(jb.getString("data"));
                    update.setDriveId(drive);
                    update.setUpdateId(jb.getString("driveId"));
                    update.setFee(sumFee);

                    if (size > 51200) {
                        String fileName = Sha256.getSHA256(jb.getString("txid") + jb.getInteger("n"));
                        String url = ouputFile(jb.getString("data"), fileName);
                        update.setData(url);
                        update.setType(1);
                    } else {
                        update.setData(jb.getString("data"));
                    }

                    updateMapper.insert(update);
                    return update.getUpdateId();

                } else {

                    DriveUtxo driveUtxo = new DriveUtxo();
                    driveUtxo.setN(jb.getInteger("n"));
                    driveUtxo.setTxid(jb.getString("txid"));
                    driveUtxo.setValue(consValue);
                    driveUtxo.setDriveId(jb.getString("driveId"));
                    driveUtxoMapper.insert(driveUtxo);

                    Create create = new Create();
//                    create.setData(jb.getString("data"));
                    create.setMetadata(jb.getString("metadata"));
                    create.setDriveId(jb.getString("driveId"));
                    create.setTxid(jb.getString("txid"));
                    create.setFee(sumFee);

                    if (size > 51200) {
                        String fileName = Sha256.getSHA256(jb.getString("txid") + jb.getInteger("n"));
                        String url = ouputFile(jb.getString("data"), fileName);
                        create.setData(url);
                        create.setType(1);
                    } else {
                        create.setData(jb.getString("data"));
                    }

                    createMapper.insert(create);
                    return create.getDriveId();


                }

            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }

        }

        return null;
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public void blockDecode(JSONArray jsonArray) throws Exception {

        for (Object ob : jsonArray) {

            String tx = (String)ob;

            Long count = driveTxAddressMapper.findByTxidCount(tx);

            if (count > 0)
                continue;

            try {

                JSONObject txTransaction = Api.GetRawTransaction(tx);

                JSONArray vins = txTransaction.getJSONArray("vin");

                Integer blockheight = txTransaction.getInteger("blockheight");

                List<String> driveList = authorityVinsList(vins);

                List<ScriptTokenLink> scriptTokenLink = null;

                try {
                    scriptTokenLink = scriptTokenLinkService.tokenVin(vins);
                } catch (Exception e) {
                    e.printStackTrace();
                }

                JSONArray vouts = txTransaction.getJSONArray("vout");

                StringBuilder data = new StringBuilder();

                JSONObject jb = new JSONObject();
                List<AddressDriveLink> addressDriveList = new ArrayList<>();

                String consValue = "";

                List<ScriptSlpSend> SlpSendList = new ArrayList<>();
                List<ScriptTokenLink> TokenAssetsList = new ArrayList<>();
                List<ScriptUtxoTokenLink> UtxoTokenList = new ArrayList<>();
                List<AddressScriptLink> addressScriptLink = new ArrayList<>();
                Boolean sendFlag = false;
                BigInteger fromAmount = new BigInteger("0");
                BigInteger toAmount = new BigInteger("0");
                Boolean flag = true;           // 销毁立flag, 如果最后是false并且当前的vin包含token，则销毁
                BalanceHistory balanceHistory = null;
                Boolean isInsert = true;

                for (Object v : vouts) {

                    JSONObject vout = (JSONObject)v;

                    BigDecimal value = vout.getBigDecimal("value");

                    Integer voutn = vout.getInteger("n");

                    DriveUtxo du = driveUtxoMapper.findByTxidAndN(tx, voutn);
                    if (du != null)
                        break;


                    ScriptUtxoTokenLink scriptUtxoTokenLink = scriptUtxoTokenLinkService.findUtxoToken(tx, voutn);
                    if (scriptUtxoTokenLink != null)
                        break;

                    String n = UnicodeUtil.intToHex(voutn);

                    JSONObject scriptPubKey =  vout.getJSONObject("scriptPubKey");

                    String type = scriptPubKey.getString("type");

                    if ("nonstandard".equals(type)) {

                        String content = scriptPubKey.getString("hex");

                        String driveId = Sha256.getSHA256(tx+n);

                        String ifHex = content.substring(0, 2);

                        if (!"63".equals(ifHex)) {
                            continue;
                        }

                        List<String> addressList = new ArrayList<>();
                        StringBuffer script = new StringBuffer();

                        content = content.replaceFirst(ifHex, "");

                        String oph = content.substring(0, 6);

                        if (!"76a914".equals(oph)) {
                            continue;
                        }

                        content = content.replaceFirst(oph, "");

                        String address1 = content.substring(0, 40);
                        addressList.add(address1);

                        FchXsvLink fchXsvLink1 = fchXsvLinkMapper.findByHash(address1);

                        if (fchXsvLink1 != null) {
                            if (!fchXsvLink1.getXsvAddress().equals(sysAddress)) {
                                AddressDriveLink addressDriveLink = new AddressDriveLink();
                                addressDriveLink.setAddress(fchXsvLink1.getAddressHash());
                                addressDriveLink.setDriveId(driveId);
                                addressDriveList.add(addressDriveLink);
                            }
                        }

                        content = content.replaceFirst(address1, "");

                        String opa = content.substring(0, 4);
                        script.append(ifHex).append(oph).append(address1).append(opa);
                        if (!"88ac".equals(opa)) {
                            continue;
                        }

                        content = content.replaceFirst(opa, "");

                        String if67 = content.substring(0, 2);

                        Boolean falg = false;

                        if ("67".equals(if67)) {

                            String fs = list67(content, addressDriveList, driveId, addressList, script);

                            if (fs == null)
                                continue;
                            else {

                                content = fs;

                                while (true) {
                                    fs = list67(content, addressDriveList, driveId, addressList, script);
                                    if (fs == null)
                                        break;
                                    content = fs;
                                    fs = content.substring(0, 2);

                                    if (!"67".equals(fs))
                                        break;
                                }

                                if (content == null)
                                    continue;
                            }
                            falg = true;
                        }

                        String a68 = content.substring(0, 2);

                        if (!"68".equals(a68) && !falg){
                            continue;
                        }

                        content = content.replaceFirst(a68, "");

                        if ("".equals(content))
                            continue;

                        String a6 = content.substring(0, 2);

                        if (!a6.equals("6a"))
                            continue;

                        content = content.replaceFirst(a6, "");

                        String totalLength_Hex = content.substring(0, 2);

                        content = content.replaceFirst(totalLength_Hex, "");

                        if ("4c".equals(totalLength_Hex)) {

                            String length_hex = content.substring(0, 2);
                            Integer length = UnicodeUtil.decodeHEX(length_hex);
                            content = content.replaceFirst(length_hex, "");
                            content = content.substring(0, length*2);


                        } else if ("4d".equals(totalLength_Hex)) {

                            String length_hex = content.substring(0, 4);
                            String b = length_hex.substring(0,2);
                            String a = length_hex.substring(2,4);
                            String c = a+b;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex, "");
                            content = content.substring(0, length*2);

                        } else if ("4e".equals(totalLength_Hex)) {

                            String length_hex = content.substring(0, 8);
                            String b = length_hex.substring(0,4);
                            String b2 = b.substring(0,2);
                            String b1 = b.substring(2,4);
                            String bb = b1 + b2;
                            String a = length_hex.substring(4,8);
                            String a2 = a.substring(0,2);
                            String a1 = a.substring(2,4);
                            String aa = a1+a2;
                            String c = aa+bb;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex, "");
                            content = content.substring(0, length*2);

                        } else {

                            Integer length = UnicodeUtil.decodeHEX(totalLength_Hex);
                            content = content.substring(0, length*2);

                        }


                        String metadata = content;

                        if (blockheight != null && blockheight > 0) {
                            Map<String, Object> map = tokenDecodeService.decodeToken(tx, vins, vouts, vout, content, scriptPubKey, voutn, script, addressList);

                            if (map != null) {

                                isInsert = false;

                                Object fob = map.get("flag");

                                if (fob != null) {
                                    if (!(Boolean) fob && flag)
                                        flag = false;
                                }

                                Object slpOb = map.get("SlpSendList");
                                if (slpOb != null) {
                                    SlpSendList.addAll((List<ScriptSlpSend>) slpOb);
                                }

                                Object tokenOb = map.get("TokenAssetsList");
                                if (tokenOb != null) {
                                    TokenAssetsList.addAll((List<ScriptTokenLink>) tokenOb);
                                }

                                Object utOb = map.get("UtxoTokenList");
                                if (utOb != null) {
                                    UtxoTokenList.addAll((List<ScriptUtxoTokenLink>) utOb);
                                }

                                Object asOb = map.get("addressScriptLink");
                                if (asOb != null) {
                                    addressScriptLink.addAll((List<AddressScriptLink>) asOb);
                                }

                                Object sfOb = map.get("sendFlag");
                                if (sfOb != null) {

                                    if ((Boolean) sfOb || sendFlag)
                                        sendFlag = true;

                                }

                                Object ta = map.get("toAmount");
                                if (ta != null) {
                                    toAmount = toAmount.add((BigInteger) ta);
                                }

                                Object fa = map.get("fromAmount");
                                if (ta != null) {
                                    fromAmount = (BigInteger) fa;
                                }
                            }
                        }

                        jb.put("driveId", driveId);
                        jb.put("metadata", metadata);
                        jb.put("n", voutn);
                        jb.put("txid", tx);

                        consValue = value.toString();


                    } else if (value.compareTo(new BigDecimal("0")) == 0) {

                        String content = scriptPubKey.getString("hex");
                        content = content.replaceFirst("006a","");
                        String length_hex = content.substring(0,2);
                        content = content.replaceFirst(length_hex,"");
                        String contentData = "";

                        if ("4c".equals(length_hex)) {

                            length_hex = content.substring(0,2);
                            Integer length = UnicodeUtil.decodeHEX(length_hex);
                            content = content.replaceFirst(length_hex,"");
                            contentData = content.substring(0, length * 2);
                            data.append(contentData);
                            jb.put("data", contentData);

                        } else if ("4d".equals(length_hex)) {

                            length_hex = content.substring(0, 4);
                            String b = length_hex.substring(0,2);
                            String a = length_hex.substring(2,4);
                            String c = a+b;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex,"");
                            contentData = content.substring(0, length * 2);
                            data.append(contentData);
                            jb.put("data", contentData);

                        } else if ("4e".equals(length_hex)) {

                            length_hex = content.substring(0, 8);
                            String b = length_hex.substring(0,4);
                            String b2 = b.substring(0,2);
                            String b1 = b.substring(2,4);
                            String bb = b1 + b2;
                            String a = length_hex.substring(4,8);
                            String a2 = a.substring(0,2);
                            String a1 = a.substring(2,4);
                            String aa = a1+a2;
                            String c = aa+bb;
                            Integer length = UnicodeUtil.decodeHEX(c);
                            content = content.replaceFirst(length_hex, "");
                            contentData = content.substring(0, length * 2);
                            data.append(contentData);
                            jb.put("data", contentData);

                        } else {

                            Integer length = UnicodeUtil.decodeHEX(length_hex);
                            contentData = content.substring(0, length*2);
                            data.append(contentData);
                            jb.put("data", contentData);

                        }
                        Integer len = contentData.length();

                        if (len >= 20) {
                            String typeInfo = contentData.substring(0, 20);

                            if ("64726976656765743031".equals(typeInfo)) {                          // get

                                balanceHistory = new BalanceHistory();
                                balanceHistory.setType("get");
                                balanceHistory.setTimestamp(new Date());
                                balanceHistory.setChange(-2);

                            } else if ("64726976657075743031".equals(typeInfo)) {                   // put

                                balanceHistory = new BalanceHistory();
                                balanceHistory.setType("put");
                                balanceHistory.setTimestamp(new Date());
                                balanceHistory.setChange(-10);

                            } else if ("64726976657570643031".equals(typeInfo)) {                   // update

                                balanceHistory = new BalanceHistory();
                                balanceHistory.setType("update");
                                balanceHistory.setTimestamp(new Date());
                                balanceHistory.setChange(-10);

                            } else if ("64726976656469643031".equals(typeInfo)) {                   // list_drive_id

                                balanceHistory = new BalanceHistory();
                                balanceHistory.setType("list_drive_id");
                                balanceHistory.setTimestamp(new Date());
                                balanceHistory.setChange(-2);


                            } else if ("64726976657464693031".equals(typeInfo)) {                   // terminate_drive_id

                                balanceHistory = new BalanceHistory();
                                balanceHistory.setType("terminate_drive_id");
                                balanceHistory.setTimestamp(new Date());
                                balanceHistory.setChange(-10);

                            } else if ("64726976656368723031".equals(typeInfo)) {                   // charge

                                balanceHistory = new BalanceHistory();
                                balanceHistory.setType("charge");
                                balanceHistory.setTimestamp(new Date());

                            }
                        }

                    }

                }

                if (blockheight != null && blockheight > 0) {

                    if (flag && sendFlag && fromAmount.compareTo(toAmount) >= 0) {

                        if (SlpSendList != null) {
                            for (ScriptSlpSend slpSend : SlpSendList) {
                                scriptSlpSendService.insertSlpSend(slpSend);
                            }
                        }

                        if (TokenAssetsList != null) {
                            for (ScriptTokenLink st : TokenAssetsList) {
                                scriptTokenLinkService.insert(st);
                                if (balanceHistory != null) {
                                    String toScript= st.getScript();
                                    List<String> list = addressScriptLinkService.findByScript(toScript);
                                    for (String l : list) {
                                        if (!l.equals("57353d54a4fc0c2d24ef12f27c4351f358855416") && !l.equals("84be1e524ff4324816f25e558dd89be1a29841b3")) {
                                            FchXsvLink xsv = fchXsvLinkMapper.findByHash(l);
                                            balanceHistory.setAddress(xsv.getFchAddress());
                                            if ("charge".equals(balanceHistory.getType())) {
                                                BigInteger token = st.getToken().divide(new BigInteger("100000000"));
                                                balanceHistory.setChange(token.intValue());
                                                balanceHistoryService.insert(balanceHistory);
                                            } else {
                                                balanceHistoryService.insert(balanceHistory);
                                            }
                                        }

                                    }

                                }
                            }
                        }

                        if (UtxoTokenList != null) {
                            for (ScriptUtxoTokenLink sut : UtxoTokenList) {
                                scriptUtxoTokenLinkService.insert(sut);
                            }
                        }

                        if (addressScriptLink != null) {
                            for (AddressScriptLink asl : addressScriptLink) {
                                addressScriptLinkService.insert(asl);
                            }
                        }



                        if (fromAmount.compareTo(toAmount) > 0) {
                            BigInteger amt = fromAmount.subtract(toAmount);

                            ScriptTokenDestruction tokenDestruction = new ScriptTokenDestruction();
                            tokenDestruction.setScript(scriptTokenLink.get(0).getScript());
                            tokenDestruction.setTxid(tx);
                            tokenDestruction.setN(scriptTokenLink.get(0).getVout());
                            scriptTokenDestructionService.insert(tokenDestruction);
                            ScriptTokenLink update = new ScriptTokenLink();
                            update.setTokenId(scriptTokenLink.get(0).getTokenId());
                            update.setStatus(3);
                            update.setTxid(tx);
                            update.setToken(amt);
                            update.setScript(scriptTokenLink.get(0).getScript());
                            scriptTokenLinkService.insert(update);

                        }

                    } else if (flag && sendFlag && fromAmount.compareTo(toAmount) < 0) {

                        ScriptTokenDestruction tokenDestruction = new ScriptTokenDestruction();
                        tokenDestruction.setScript(scriptTokenLink.get(0).getScript());
                        tokenDestruction.setTxid(tx);
                        tokenDestruction.setN(scriptTokenLink.get(0).getVout());
                        scriptTokenDestructionService.insert(tokenDestruction);
                        ScriptTokenLink update = new ScriptTokenLink();
                        update.setTokenId(scriptTokenLink.get(0).getTokenId());
                        update.setStatus(3);
                        update.setTxid(tx);
                        update.setToken(fromAmount);
                        update.setScript(scriptTokenLink.get(0).getScript());
                        scriptTokenLinkService.insert(update);

                        BalanceHistory bh = new BalanceHistory();
                        String a = fromAmount.divide(new BigInteger("100000000")).toString();
                        bh.setChange(Integer.valueOf("-" + a));
                        bh.setTimestamp(new Date());
                        bh.setType("destruction");
                        List<String> adHash = addressScriptLinkService.findByScript(scriptTokenLink.get(0).getScript());
                        for (String ad : adHash) {
                            FchXsvLink address = fchXsvLinkMapper.findByHash(ad);
                            if (address != null && (!sysAddress.equals(address.getFchAddress()) && !sysTokenAddress.equals(address.getFchAddress()))) {
                                bh.setAddress(address.getFchAddress());
                                balanceHistoryService.insert(bh);
                            }
                        }


                    }

                    if (!flag && scriptTokenLink != null) {            //销毁

                        if (scriptTokenLink != null) {

                            for (ScriptTokenLink scriptToken : scriptTokenLink) {

                                ScriptTokenDestruction tokenDestruction = new ScriptTokenDestruction();
                                tokenDestruction.setScript(scriptToken.getScript());
                                tokenDestruction.setTxid(tx);
                                tokenDestruction.setN(scriptToken.getVout());
                                scriptTokenDestructionService.insert(tokenDestruction);
                                ScriptTokenLink update = new ScriptTokenLink();
                                update.setTokenId(scriptToken.getTokenId());
                                update.setStatus(3);
                                update.setTxid(tx);
                                update.setToken(scriptToken.getToken());
                                update.setScript(scriptToken.getScript());
                                scriptTokenLinkService.insert(update);

                            }
                        }
                    }

                }

                String md = jb.getString("metadata");
                if (md != null && md.length() > 19) {
                    String sub = md.substring(0, 20);
                    if ("06534c502b2b00020201".equals(sub)) {
                        isInsert = false;
                    }
                }

                if (isInsert) {

                    for (AddressDriveLink addressDrive : addressDriveList) {

                        if (!StringUtils.isEmpty(jb.getString("driveId"))) {
                            DriveTxAddress driveTxAddress = new DriveTxAddress();
                            driveTxAddress.setAddress(addressDrive.getAddress());
                            driveTxAddress.setN(jb.getInteger("n"));
                            driveTxAddress.setTxid(jb.getString("txid"));
                            driveTxAddress.setCreateDate(new Date());
                            if (driveList != null && driveList.size() > 0) {
                                driveTxAddress.setDriveId(driveList.get(0));
                                driveTxAddress.setUpdateId(jb.getString("driveId"));
                                addressDrive.setStatus(1);
                            } else {
                                driveTxAddress.setDriveId(jb.getString("driveId"));
                                addressDrive.setStatus(0);
                            }

                            driveTxAddressMapper.insert(driveTxAddress);
                            addressDriveLinkMapper.insert(addressDrive);
                        }
                    }


                    if (driveList != null && driveList.size() > 0) {

                        if (!StringUtils.isEmpty(jb.getString("driveId"))) {
                            DriveUtxo driveUtxo = new DriveUtxo();
                            driveUtxo.setN(jb.getInteger("n"));
                            driveUtxo.setTxid(jb.getString("txid"));
                            driveUtxo.setValue(consValue);
                            driveUtxo.setDriveId(driveList.get(0));
                            driveUtxoMapper.insert(driveUtxo);

                            Update update = new Update();
                            update.setMetadata(jb.getString("metadata"));
                            update.setDriveId(driveList.get(0));
                            update.setUpdateId(jb.getString("driveId"));
                            update.setCreateDate(new Date());
                            if (!StringUtils.isEmpty(jb.getString("data"))) {
                                Integer size = jb.getString("data").getBytes().length;
                                if (size > 51200) {
                                    String fileName = Sha256.getSHA256(jb.getString("txid") + jb.getInteger("n"));
                                    String url = ouputFile(jb.getString("data"), fileName);
                                    update.setData(url);
                                    update.setType(1);
                                } else {
                                    update.setData(jb.getString("data"));
                                }
                                updateMapper.insert(update);
                            }
                        }

                    } else {

                        if (jb.size() > 0) {

                            if (!StringUtils.isEmpty(jb.getString("driveId"))) {
                                DriveUtxo driveUtxo = new DriveUtxo();
                                driveUtxo.setN(jb.getInteger("n"));
                                driveUtxo.setTxid(jb.getString("txid"));
                                driveUtxo.setValue(consValue);
                                driveUtxo.setDriveId(jb.getString("driveId"));
                                driveUtxoMapper.insert(driveUtxo);

                                Create create = new Create();
                                create.setMetadata(jb.getString("metadata"));
                                create.setDriveId(jb.getString("driveId"));
                                create.setTxid(jb.getString("txid"));
                                create.setCreateDate(new Date());
                                if (!StringUtils.isEmpty(jb.getString("data"))) {
                                    Integer size = jb.getString("data").getBytes().length;
                                    if (size > 51200) {
                                        String fileName = Sha256.getSHA256(jb.getString("txid") + jb.getInteger("n"));
                                        String url = ouputFile(jb.getString("data"), fileName);
                                        create.setData(url);
                                        create.setType(1);
                                    } else {
                                        create.setData(jb.getString("data"));
                                    }
                                }

                                createMapper.insert(create);
                            }

                        }

                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }

        }


    }


    public boolean authorityVins(JSONArray vins) {

        boolean flag = false;
        for (Object v: vins) {

            JSONObject vin = (JSONObject) v;
            String txid = vin.getString("txid");
            Integer vout = vin.getInteger("vout");

            DriveUtxo du = driveUtxoMapper.findByTxidAndN(txid, vout);
            if (du != null) {
                flag = true;
                DriveUtxo DriveUtxo = new DriveUtxo();
                DriveUtxo.setTxid(txid);
                DriveUtxo.setN(vout);
                driveUtxoMapper.delete(DriveUtxo);
            }

        }

        return flag;
    }


    public List<String> authorityVinsList(JSONArray vins) {

        List<String> driveList = new ArrayList<>();

        for (Object v: vins) {

            JSONObject vin = (JSONObject) v;
            String txid = vin.getString("txid");
            Integer vout = vin.getInteger("vout");

            DriveUtxo du = driveUtxoMapper.findByTxidAndN(txid, vout);
            if (du != null) {
                DriveUtxo DriveUtxo = new DriveUtxo();
                DriveUtxo.setTxid(txid);
                DriveUtxo.setN(vout);
                driveUtxoMapper.delete(DriveUtxo);
                driveList.add(du.getDriveId());
            }

        }

        return driveList;
    }

    public String list67(String content, List<AddressDriveLink> addressDriveList, String driveId, List<String> addressList, StringBuffer script) {

        String if67 = content.substring(0, 2);

        content = content.replaceFirst(if67, "");

        if ("67".equals(if67)) {

            String oph1 = content.substring(0, 6);

            if (!"76a914".equals(oph1)) {
                return null;
            }

            content = content.replaceFirst(oph1, "");
            String address2 = content.substring(0, 40);
            addressList.add(address2);

            FchXsvLink fchXsvLink2 = fchXsvLinkMapper.findByHash(address2);

            if (fchXsvLink2 != null) {
                if (!fchXsvLink2.getXsvAddress().equals(sysAddress)) {
                    AddressDriveLink addressDriveLink = new AddressDriveLink();
                    addressDriveLink.setAddress(fchXsvLink2.getAddressHash());
                    addressDriveLink.setDriveId(driveId);
                    addressDriveList.add(addressDriveLink);
                }
            }

            content = content.replaceFirst(address2, "");

            String opa = content.substring(0, 4);

            script.append(if67).append(oph1).append(address2).append(opa);

            if (!"88ac".equals(opa)) {
                return null;
            }

            content = content.replaceFirst(opa, "");

            return content;

        }

        return null;

    }


    public String ouputFile(String data, String fileName) throws Exception {

        File dest = new File(deposeFilesDir + fileName+".txt");

        if (!dest.getParentFile().exists()) {
            dest.getParentFile().mkdirs();
        }

        BufferedOutputStream bos = null;

        try {

            bos = new BufferedOutputStream(new FileOutputStream(dest)) ;
            bos.write(data.getBytes());
            bos.close();

        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (bos != null) {
                bos.close();
            }
        }
        String url = "http://localhost:8442/api/download?data=";
        return url + fileName;

    }


}
