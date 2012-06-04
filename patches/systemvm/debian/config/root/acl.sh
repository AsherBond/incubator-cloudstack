#!/usr/bin/env bash
# Copyright 2012 Citrix Systems, Inc. Licensed under the
# Apache License, Version 2.0 (the "License"); you may not use this
# file except in compliance with the License.  Citrix Systems, Inc.
# reserves all rights not expressly granted by the License.
# You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# 
# Automatically generated by addcopyright.py at 04/03/2012
# firewall_rule.sh -- allow some ports / protocols to vm instances
# @VERSION@

source /root/func.sh

lock="biglock"
locked=$(getLockFile $lock)
if [ "$locked" != "1" ]
then
    exit 1
fi

usage() {
  printf "Usage: %s:  -a <public ip address:protocol:startport:endport:sourcecidrs>  \n" $(basename $0) >&2
  printf "sourcecidrs format:  cidr1-cidr2-cidr3-...\n"
}
#set -x
#FIXME: eating up the error code during execution of iptables
acl_remove_backup() {
  sudo iptables -F _ACL_INBOND_$gGW 2>/dev/null
  sudo iptables -D FORWARD -o $dev -d $gcidr -j _ACL_INBOND_$gGW  2>/dev/null
  sudo iptables -X _ACL_INBOND_$gGW 2>/dev/null
  sudo iptables -F _ACL_OUTBOND_$gGW 2>/dev/null
  sudo iptables -D FORWARD -i $dev -s $gcidr -j _ACL_OUTBOND_$gGW  2>/dev/null
  sudo iptables -X _ACL_OUTBOND_$gGW 2>/dev/null
}

acl_remove() {
  sudo iptables -F ACL_INBOND_$gGW 2>/dev/null
  sudo iptables -D FORWARD -o $dev -d $gcidr -j ACL_INBOND_$gGW  2>/dev/null
  sudo iptables -X ACL_INBOND_$gGW 2>/dev/null
  sudo iptables -F ACL_OUTBOND_$gGW 2>/dev/null
  sudo iptables -D FORWARD -i $dev -s $gcidr -j ACL_OUTBOND_$gGW  2>/dev/null
  sudo iptables -X ACL_OUTBOND_$gGW 2>/dev/null
}

acl_restore() {
  acl_remove
  sudo iptables -E _ACL_INBOND_$gGW ACL_INBOND_$gGW 2>/dev/null
  sudo iptables -E _ACL_OUTBOND_$gGW ACL_OUTBOND_$gGW 2>/dev/null
}

acl_save() {
  acl_remove_backup
  sudo iptables -E ACL_INBOND_$gGW _ACL_INBOND_$gGW 2>/dev/null
  sudo iptables -E ACL_OUTBOND_$gGW _ACL_OUTBOND_$gGW 2>/dev/null
}

acl_chain_for_guest_network () {
  acl_save
  # inbond
  sudo iptables -E ACL_INBOND_$gGW _ACL_INBOND_$gGW 2>/dev/null
  sudo iptables -N ACL_INBOND_$gGW 2>/dev/null
  # drop if no rules match (this will be the last rule in the chain)
  sudo iptables -A ACL_INBOND_$gGW -j DROP 2>/dev/null
  sudo iptables -A FORWARD -o $dev -d $gcidr -j ACL_INBOND_$gGW  2>/dev/null
  # outbond
  sudo iptables -E ACL_OUTBOND_$gGW _ACL_OUTBOND_$gGW 2>/dev/null
  sudo iptables -N ACL_OUTBOND_$gGW 2>/dev/null
  sudo iptables -A ACL_OUTBOND_$gGW -j DROP 2>/dev/null
  sudo iptables -D FORWARD -i $dev -s $gcidr -j ACL_OUTBOND_$gGW  2>/dev/null
}



acl_entry_for_guest_network() {
  local rule=$1

  local inbond=$(echo $rule | cut -d: -f1)
  local prot=$(echo $rules | cut -d: -f2)
  local sport=$(echo $rules | cut -d: -f3)    
  local eport=$(echo $rules | cut -d: -f4)    
  local cidrs=$(echo $rules | cut -d: -f5 | sed 's/-/ /g')
  
  logger -t cloud "$(basename $0): enter apply firewall rules for guest network: $gcidr inbond:$inbond:$prot:$sport:$eport:$cidrs"  

  # note that rules are inserted after the RELATED,ESTABLISHED rule 
  # but before the DROP rule
  for lcidr in $scidrs
  do
    [ "$prot" == "reverted" ] && continue;
    if [ "$prot" == "icmp" ]
    then
      typecode="$sport/$eport"
      [ "$eport" == "-1" ] && typecode="$sport"
      [ "$sport" == "-1" ] && typecode="any"
      if [ "$inbond" == "1" ]
      then
        sudo iptables -I ACL_INBOND_$gGW -p $prot -s $lcidr  \
                    --icmp-type $typecode  -j ACCEPT
      else
        sudo iptables -I ACL_OUTBOND_$gGW -p $prot -d $lcidr  \
                    --icmp-type $typecode  -j ACCEPT
      fi
    else
      if [ "$inbond" == "1" ]
      then
        sudo iptables -I ACL_INBOND_$gGW -p $prot -s $lcidr \
                    --dport $sport:$eport -j ACCEPT
      else
        sudo iptables -I ACL_OUTBOND_$gGW -p $prot -d $lcidr \
                    --dport $sport:$eport -j ACCEP`T
    fi
    result=$?
    [ $result -gt 0 ] && 
       logger -t cloud "Error adding iptables entry for $pubIp:$prot:$sport:$eport:$src" &&
       break
  done
      
  logger -t cloud "$(basename $0): exit apply firewall rules for public ip $pubIp"  
  return $result
}


shift 
dflag=0
gflag=0
aflag=0
rules=""
rules_list=""
gcidr=""
gGW=""
dev=""
while getopts ':d:g:a:' OPTION
do
  case $OPTION in
  d)    dflag=1
                dev="$OPTAGR"
  g)    gflag=1
                gcidr="$OPTAGR"
  a)	aflag=1
		rules="$OPTARG"
		;;
  ?)	usage
                unlock_exit 2 $lock $locked
		;;
  esac
done

VIF_LIST=$(get_vif_list)

if [ "$gflag$aflag" != "11" ]
then
  usage()
fi


if [ -n "$rules" == "" ]
then
  rules_list=$(echo $rules | cut -d, -f1- --output-delimiter=" ")
fi

# rule format
# protocal:sport:eport:cidr
#-a tcp:80:80:0.0.0.0/0::tcp:220:220:0.0.0.0/0:,172.16.92.44:tcp:222:222:192.168.10.0/24-75.57.23.0/22-88.100.33.1/32
#    if any entry is reverted , entry will be in the format <ip>:reverted:0:0:0
# example : 172.16.92.44:tcp:80:80:0.0.0.0/0:,172.16.92.44:tcp:220:220:0.0.0.0/0:,200.1.1.2:reverted:0:0:0 

success=0
gGW=$(echo $gcidr | awk -F'/' '{print $1}')

acl_chain_for_guest_network

for r in $rules_list
do
  acl_entry_for_guest_network $r
  success=$?
  if [ $success -gt 0 ]
  then
    logger -t cloud "$(basename $0): failure to apply fw rules for guest network: $gcidr"
    break
  else
    logger -t cloud "$(basename $0): successful in applying fw rules for guest network: $gcidr"
  fi
done

if [ $success -gt 0 ]
then
  logger -t cloud "$(basename $0): restoring from backup for guest network: $gcidr"
  acl_restore
else
  logger -t cloud "$(basename $0): deleting backup for guest network: $gcidr"
  acl_remove_backup
fi
unlock_exit $success $lock $locked
